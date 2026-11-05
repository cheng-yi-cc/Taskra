package com.taskra.sync

import androidx.sqlite.db.SupportSQLiteDatabase

/** 日志默认关闭；启用后修改记录与业务写入处于同一 SQLite 事务。 */
internal object SyncJournalSchema {
    fun hasLocalPending(db: SupportSQLiteDatabase, kind: String, localId: String): Boolean = db.query(
        androidx.sqlite.db.SimpleSQLiteQuery(
            """SELECT 1 FROM sync_dirty WHERE kind=? AND record_id=?
               UNION ALL SELECT 1 FROM sync_outbox WHERE kind=? AND record_id=? LIMIT 1""",
            arrayOf(kind, localId, kind, localId),
        )
    ).use { it.moveToFirst() }

    /** 设备绑定仅能在同步尚未启用时设置；重新配对应走显式安全迁移流程。 */
    fun bindDevice(db: SupportSQLiteDatabase, deviceId: String) {
        check(db.inTransaction()) { "绑定同步设备需要事务" }
        require(Regex("[A-Za-z0-9_-]+").matches(deviceId)) { "设备标识无效" }
        val state = db.query("SELECT enabled, binding FROM sync_control WHERE id=1").use {
            check(it.moveToFirst()); it.getInt(0) to it.getString(1)
        }
        check(state.first == 0 && (state.second.isEmpty() || state.second == deviceId)) { "已有同步设备绑定" }
        db.execSQL("UPDATE sync_control SET binding=? WHERE id=1", arrayOf(deviceId))
    }

    /** 拉取游标只能在服务端事件与胜出版本已持久化到收件箱后推进。 */
    fun enqueueInbox(db: SupportSQLiteDatabase, binding: String, expectedAfter: Long, changes: List<RemoteChange>): Boolean {
        check(db.inTransaction()) { "接收同步记录需要事务" }
        require(changes.size <= 500 && expectedAfter >= 0)
        val state = db.query("SELECT binding, pull_cursor FROM sync_control WHERE id=1").use {
            check(it.moveToFirst()); it.getString(0) to it.getLong(1)
        }
        check(binding.isNotBlank() && state.first == binding) { "同步设备绑定不匹配" }
        if (state.second != expectedAfter) return false
        var last = expectedAfter
        changes.forEach { change ->
            val head = change.head
            require(change.cursor > last && head.kind in setOf("semester", "course", "homework", "draft", "image", "review", "preferences"))
            require(head.key.isNotBlank() && head.key.length <= 256 && head.modifiedAt >= 0 && head.sequence >= 0)
            require(head.deviceId.length <= 128 && Regex("[A-Za-z0-9_-]+").matches(head.deviceId))
            db.execSQL(
                "INSERT INTO sync_inbox(cursor,kind,record_id,head_json) VALUES (?,?,?,?)",
                arrayOf(change.cursor, head.kind, head.key, encodeRemoteRecord(head)),
            )
            last = change.cursor
        }
        if (changes.isNotEmpty()) db.execSQL("UPDATE sync_control SET pull_cursor=? WHERE id=1", arrayOf(last))
        return true
    }

    /** 已安全应用最新版本后，在同一事务内移除对应的旧事件。 */
    fun consumeInbox(db: SupportSQLiteDatabase, kind: String, cloudId: String, localId: String, through: Long) {
        check(db.inTransaction()) { "确认已应用版本需要事务" }
        check(!hasLocalPending(db, kind, localId)) { "本机仍有未上传的修改" }
        val exactExists = db.query(androidx.sqlite.db.SimpleSQLiteQuery(
            "SELECT 1 FROM sync_inbox WHERE kind=? AND record_id=? AND cursor=?", arrayOf(kind, cloudId, through),
        )).use { it.moveToFirst() }
        check(exactExists) { "找不到对应的远端版本" }
        val newerExists = db.query(androidx.sqlite.db.SimpleSQLiteQuery(
            "SELECT 1 FROM sync_inbox WHERE kind=? AND record_id=? AND cursor>? LIMIT 1", arrayOf(kind, cloudId, through),
        )).use { it.moveToFirst() }
        check(!newerExists) { "存在更新的远端版本" }
        db.execSQL("DELETE FROM sync_inbox WHERE kind=? AND record_id=? AND cursor<=?", arrayOf(kind, cloudId, through))
    }

    /** 仅确认发送时的版本，发送过程中新增的本地修改必须留待下次同步。 */
    fun acknowledge(db: SupportSQLiteDatabase, kind: String, recordId: String, sequence: Long) {
        check(db.inTransaction()) { "确认同步需要事务" }
        val staged = db.query(
            androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT 1 FROM sync_outbox WHERE kind=? AND record_id=? AND sequence=?",
                arrayOf(kind, recordId, sequence),
            )
        ).use { it.moveToFirst() }
        check(staged) { "找不到对应的待同步快照" }
        db.execSQL(
            """DELETE FROM sync_initial WHERE kind=? AND record_id=? AND EXISTS
               (SELECT 1 FROM sync_dirty WHERE kind=? AND record_id=? AND sequence=?)""",
            arrayOf(kind, recordId, kind, recordId, sequence),
        )
        db.execSQL(
            "DELETE FROM sync_dirty WHERE kind = ? AND record_id = ? AND sequence = ?",
            arrayOf(kind, recordId, sequence),
        )
        db.execSQL(
            "DELETE FROM sync_outbox WHERE kind = ? AND record_id = ? AND sequence = ?",
            arrayOf(kind, recordId, sequence),
        )
    }

    /** 服务器明确拒绝首次覆盖后，若本机已产生新版本，只丢弃未被接受的旧快照。 */
    fun discardRejectedInitialIfChanged(db: SupportSQLiteDatabase, kind: String, recordId: String, sequence: Long): Boolean {
        check(db.inTransaction()) { "处理同步冲突需要事务" }
        val payload = db.query(androidx.sqlite.db.SimpleSQLiteQuery(
            "SELECT payload FROM sync_outbox WHERE kind=? AND record_id=? AND sequence=?", arrayOf(kind, recordId, sequence),
        )).use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
        val current = db.query(androidx.sqlite.db.SimpleSQLiteQuery(
            "SELECT sequence FROM sync_dirty WHERE kind=? AND record_id=?", arrayOf(kind, recordId),
        )).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return false
        if (current == sequence || !decodeOutgoingChange(payload).ifAbsent) return false
        db.execSQL("DELETE FROM sync_outbox WHERE kind=? AND record_id=? AND sequence=?", arrayOf(kind, recordId, sequence))
        return true
    }

    /**
     * 冻结一个经过附件持久化和校验的待发送版本。若制作快照期间发生新编辑则不入队。
     * 每次重试复用完全相同的内容与时钟判定；调用方须在事务中执行。
     */
    fun freeze(db: SupportSQLiteDatabase, kind: String, recordId: String, sequence: Long, observedDatabaseSequence: Long, payload: String): Boolean {
        check(db.inTransaction()) { "冻结待同步记录需要事务" }
        if (databaseSequence(db) != observedDatabaseSequence) return false
        val pending = db.query(
            androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT sequence FROM sync_dirty WHERE kind=? AND record_id=?", arrayOf(kind, recordId),
            )
        ).use { it.moveToFirst() && it.getLong(0) == sequence }
        if (!pending) return false
        db.execSQL(
            "INSERT OR IGNORE INTO sync_outbox(kind,record_id,sequence,payload) VALUES (?,?,?,?)",
            arrayOf(kind, recordId, sequence, payload),
        )
        val saved = db.query(
            androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT payload FROM sync_outbox WHERE kind=? AND record_id=? AND sequence=?",
                arrayOf(kind, recordId, sequence),
            )
        ).use { check(it.moveToFirst()); it.getString(0) }
        check(saved == payload) { "同一同步版本的内容不一致" }
        return true
    }

    /** 快照读取时与业务数据在同一事务中读取；远端应用须与快照过程互斥。 */
    fun databaseSequence(db: SupportSQLiteDatabase): Long = db.query(
        "SELECT next_sequence FROM sync_control WHERE id=1"
    ).use { check(it.moveToFirst()); it.getLong(0) }

    fun markPreferences(db: SupportSQLiteDatabase, modifiedAt: Long, onlyIfAbsent: Boolean) {
        check(db.inTransaction())
        db.execSQL("UPDATE sync_control SET next_sequence=next_sequence+1 WHERE id=1")
        db.execSQL("INSERT OR IGNORE INTO sync_dirty(kind,record_id,modified_at,sequence,deleted) VALUES ('preferences','settings',0,0,0)")
        db.execSQL("UPDATE sync_dirty SET modified_at=?, sequence=(SELECT next_sequence FROM sync_control WHERE id=1), deleted=0 WHERE kind='preferences' AND record_id='settings'", arrayOf(modifiedAt))
        if (onlyIfAbsent) db.execSQL("INSERT OR IGNORE INTO sync_initial VALUES ('preferences','settings')")
        else db.execSQL("DELETE FROM sync_initial WHERE kind='preferences' AND record_id='settings'")
    }

    /** 首次上传旧数据；调用方须在事务中执行，并先确认服务器允许以本机初始化。 */
    fun bootstrap(db: SupportSQLiteDatabase, onlyIfAbsent: Boolean = false) {
        check(db.inTransaction()) { "初始化同步日志需要事务" }
        val complete = db.query("SELECT bootstrap_complete FROM sync_control WHERE id=1").use {
            check(it.moveToFirst()) { "同步控制记录不存在" }
            it.getInt(0) != 0
        }
        if (complete) return
        db.execSQL("UPDATE sync_control SET enabled=1 WHERE id=1")
        val now = System.currentTimeMillis()
        listOf(
            "semesters" to "semester", "courses" to "course", "homework" to "homework",
            "editor_drafts" to "draft", "image_assets" to "image", "review_sessions" to "review",
        ).forEach { (table, kind) ->
            db.query("SELECT id FROM $table").use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val alreadyPending = db.query(androidx.sqlite.db.SimpleSQLiteQuery(
                        "SELECT 1 FROM sync_dirty WHERE kind=? AND record_id=?", arrayOf(kind, id),
                    )).use { it.moveToFirst() }
                    if (alreadyPending) continue
                    db.execSQL("UPDATE sync_control SET next_sequence=next_sequence+1 WHERE id=1")
                    db.execSQL(
                        """INSERT OR IGNORE INTO sync_dirty(kind,record_id,modified_at,sequence,deleted)
                            VALUES (?, ?, ?, (SELECT next_sequence FROM sync_control WHERE id=1), 0)""",
                        arrayOf(kind, id, now),
                    )
                    if (onlyIfAbsent) db.execSQL("INSERT OR IGNORE INTO sync_initial VALUES (?,?)", arrayOf(kind, id))
                }
            }
        }
        db.execSQL("UPDATE sync_control SET bootstrap_complete=1 WHERE id=1")
    }

    fun install(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_control (
            id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
            next_sequence INTEGER NOT NULL, applying_remote INTEGER NOT NULL, bootstrap_complete INTEGER NOT NULL,
            enabled INTEGER NOT NULL, pull_cursor INTEGER NOT NULL, binding TEXT NOT NULL
        )""")
        db.execSQL("INSERT OR IGNORE INTO sync_control VALUES (1, 0, 0, 0, 0, 0, '')")
        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_dirty (
            kind TEXT NOT NULL, record_id TEXT NOT NULL, modified_at INTEGER NOT NULL,
            sequence INTEGER NOT NULL, deleted INTEGER NOT NULL,
            PRIMARY KEY (kind, record_id)
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_outbox (
            kind TEXT NOT NULL, record_id TEXT NOT NULL, sequence INTEGER NOT NULL,
            payload TEXT NOT NULL, PRIMARY KEY (kind, record_id, sequence)
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_inbox (
            cursor INTEGER NOT NULL PRIMARY KEY, kind TEXT NOT NULL, record_id TEXT NOT NULL, head_json TEXT NOT NULL
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_initial (
            kind TEXT NOT NULL, record_id TEXT NOT NULL, PRIMARY KEY (kind, record_id)
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_preferences (
            id INTEGER NOT NULL PRIMARY KEY CHECK(id=1), snapshot TEXT NOT NULL,
            apply_cursor INTEGER NOT NULL, apply_target TEXT NOT NULL
        )""")
        db.execSQL("INSERT OR IGNORE INTO sync_preferences VALUES (1,'',0,'')")

        fun mark(kind: String, expression: String, deleted: String): String = """
            DELETE FROM sync_initial WHERE kind = '$kind' AND record_id = $expression;
            INSERT OR IGNORE INTO sync_dirty(kind, record_id, modified_at, sequence, deleted)
                VALUES ('$kind', $expression, 0, 0, 0);
            UPDATE sync_control SET next_sequence = next_sequence + 1 WHERE id = 1;
            UPDATE sync_dirty SET modified_at = CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER),
                sequence = (SELECT next_sequence FROM sync_control WHERE id = 1), deleted = $deleted
                WHERE kind = '$kind' AND record_id = $expression;
        """.trimIndent()

        fun trigger(name: String, operation: String, table: String, body: String) {
            db.execSQL("DROP TRIGGER IF EXISTS sync_$name")
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS sync_$name AFTER $operation ON $table
                WHEN COALESCE((SELECT applying_remote FROM sync_control WHERE id = 1), 0) = 0
                  AND COALESCE((SELECT enabled FROM sync_control WHERE id = 1), 0) = 1
                BEGIN $body END
            """.trimIndent())
        }

        listOf(
            "semesters" to "semester", "courses" to "course", "homework" to "homework",
            "editor_drafts" to "draft", "image_assets" to "image", "review_sessions" to "review",
        ).forEach { (table, kind) ->
            trigger("${kind}_insert", "INSERT", table, mark(kind, "NEW.id", "0"))
            trigger("${kind}_update", "UPDATE", table, mark(kind, "NEW.id", "0"))
            trigger("${kind}_delete", "DELETE", table, mark(kind, "OLD.id", "1"))
        }
        fun homeworkExists(id: String) = "CASE WHEN EXISTS (SELECT 1 FROM homework WHERE id = $id) THEN 0 ELSE 1 END"
        trigger("block_insert", "INSERT", "content_blocks", mark("homework", "NEW.homeworkId", homeworkExists("NEW.homeworkId")))
        trigger("block_delete", "DELETE", "content_blocks", mark("homework", "OLD.homeworkId", homeworkExists("OLD.homeworkId")))
        trigger("block_update", "UPDATE", "content_blocks",
            mark("homework", "OLD.homeworkId", homeworkExists("OLD.homeworkId")) +
                mark("homework", "NEW.homeworkId", homeworkExists("NEW.homeworkId")))
        fun reviewExists(id: String) = "CASE WHEN EXISTS (SELECT 1 FROM review_sessions WHERE id = $id) THEN 0 ELSE 1 END"
        trigger("review_item_insert", "INSERT", "review_items", mark("review", "NEW.sessionId", reviewExists("NEW.sessionId")))
        trigger("review_item_delete", "DELETE", "review_items", mark("review", "OLD.sessionId", reviewExists("OLD.sessionId")))
        trigger("review_item_update", "UPDATE", "review_items",
            mark("review", "OLD.sessionId", reviewExists("OLD.sessionId")) +
                mark("review", "NEW.sessionId", reviewExists("NEW.sessionId")))
    }
}
