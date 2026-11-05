package com.taskra.sync

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.taskra.data.AppDatabase
import com.taskra.data.PrefsRepository
import com.taskra.data.SortMode
import com.taskra.data.ThemeMode
import com.taskra.data.UserPrefs
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

private val PreferencesJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

/**
 * DataStore 与 Room 无法共享事务。数据库保存上次观察值和远端应用意图，
 * 崩溃后先恢复意图，再识别离线本地修改，避免把已接收的设置误判成新编辑。
 */
internal class SyncPreferences(private val db: AppDatabase, private val prefs: PrefsRepository) {
    private data class State(val snapshot: String, val cursor: Long, val target: String)
    private fun encoded(p: UserPrefs): String = PreferencesJson.encodeToString(
        p.copy(lastBackupTime = 0, listOrders = p.listOrders.toSortedMap())
    )
    private fun validated(raw: String): UserPrefs {
        val p = PreferencesJson.decodeFromString<UserPrefs>(raw)
        require(p.mottoText.length <= 2000 && p.lastBackupTime == 0L)
        require(p.themeMode in setOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK))
        require(p.sortMode in setOf(SortMode.MANUAL, SortMode.DEADLINE, SortMode.NEWEST, SortMode.OLDEST))
        require(p.reminderMode in setOf("DAY_TIME", "BEFORE_DEADLINE") && p.reminderMinuteOfDay in 0..1439 && p.reminderHours in 1..168)
        require(p.listOrders.size <= 10_000 && p.listOrders.all { (key, ids) ->
            key.length in 1..256 && ids.size <= 100_000 && ids.all { it.length in 1..256 }
        })
        require(p.currentSemesterId == null || p.currentSemesterId.length in 1..256)
        return p
    }

    private fun state(sql: androidx.sqlite.db.SupportSQLiteDatabase): State =
        sql.query("SELECT snapshot,apply_cursor,apply_target FROM sync_preferences WHERE id=1").use {
            check(it.moveToFirst()); State(it.getString(0), it.getLong(1), it.getString(2))
        }

    suspend fun needsInitialization(): Boolean = db.withTransaction {
        state(db.openHelper.writableDatabase).snapshot.isEmpty()
    }

    /** 初始服务器已有数据时只接收设置，不能把空白设备的默认值作为新版本上传。 */
    suspend fun reconcile(remoteHasData: Boolean) = SyncGates.operation.withLock {
        SyncGates.preferences.withLock {
            val sql = db.openHelper.writableDatabase
            var previous = db.withTransaction { state(sql) }
            var (local, changedAt) = prefs.syncSnapshot()
            var current = encoded(local)
            if (previous.cursor != 0L) {
                val target = validated(previous.target)
                if (current == previous.snapshot) {
                    prefs.applySynchronized(target)
                    local = prefs.syncSnapshot().first
                    current = encoded(local)
                }
                if (current == previous.target) {
                    db.withTransaction {
                        SyncJournalSchema.consumeInbox(sql, "preferences", "settings", "settings", previous.cursor)
                        sql.execSQL("UPDATE sync_preferences SET snapshot=?,apply_cursor=0,apply_target='' WHERE id=1", arrayOf(current))
                    }
                } else {
                    // 意图写入后用户又编辑过，保留本地修改，远端仍留在收件箱。
                    db.withTransaction { sql.execSQL("UPDATE sync_preferences SET apply_cursor=0,apply_target='' WHERE id=1") }
                }
                previous = db.withTransaction { state(sql) }
            }
            db.withTransaction {
                if (previous.snapshot.isEmpty()) {
                    sql.execSQL("UPDATE sync_preferences SET snapshot=? WHERE id=1", arrayOf(current))
                    if (!remoteHasData) SyncJournalSchema.markPreferences(sql, changedAt.takeIf { it > 0 } ?: System.currentTimeMillis(), true)
                } else if (current != previous.snapshot) {
                    sql.execSQL("UPDATE sync_preferences SET snapshot=? WHERE id=1", arrayOf(current))
                    SyncJournalSchema.markPreferences(sql, changedAt.takeIf { it > 0 } ?: System.currentTimeMillis(), false)
                }
            }
        }
    }

    suspend fun apply(cursor: Long): ApplyResult = SyncGates.operation.withLock {
        SyncGates.preferences.withLock {
            val sql = db.openHelper.writableDatabase
            val head = db.withTransaction {
                val raw = sql.query(SimpleSQLiteQuery("SELECT head_json FROM sync_inbox WHERE cursor=?", arrayOf(cursor))).use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: return@withTransaction null
                decodeRemoteRecord(raw)
            } ?: return@withLock ApplyResult.MISSING
            require(head.kind == "preferences" && head.key == "settings" && head.value != null)
            val outer = head.value as? JsonObject ?: error("设置记录无效")
            require((outer["attachments"] as? JsonArray)?.isEmpty() == true)
            val payload = PreferencesJson.decodeFromJsonElement<SyncPayload>(requireNotNull(outer["payload"]))
            require(payload.assets.isEmpty())
            val target = encoded(validated(payload.document.toString()))
            val (local, changedAt) = prefs.syncSnapshot()
            val current = encoded(local)
            val ready = db.withTransaction {
                val previous = state(sql)
                check(previous.snapshot.isNotEmpty() && previous.cursor == 0L)
                if (current != previous.snapshot) {
                    sql.execSQL("UPDATE sync_preferences SET snapshot=? WHERE id=1", arrayOf(current))
                    SyncJournalSchema.markPreferences(sql, changedAt.takeIf { it > 0 } ?: System.currentTimeMillis(), false)
                }
                if (SyncJournalSchema.hasLocalPending(sql, "preferences", "settings")) return@withTransaction false
                val latest = sql.query("SELECT MAX(cursor) FROM sync_inbox WHERE kind='preferences' AND record_id='settings'").use {
                    check(it.moveToFirst()); it.getLong(0)
                }
                if (latest != cursor) return@withTransaction false
                sql.execSQL("UPDATE sync_preferences SET apply_cursor=?,apply_target=? WHERE id=1", arrayOf(cursor, target))
                true
            }
            if (!ready) return@withLock ApplyResult.WAITING_LOCAL
            prefs.applySynchronized(validated(target))
            db.withTransaction {
                SyncJournalSchema.consumeInbox(sql, "preferences", "settings", "settings", cursor)
                sql.execSQL("UPDATE sync_preferences SET snapshot=?,apply_cursor=0,apply_target='' WHERE id=1", arrayOf(target))
            }
            ApplyResult.APPLIED
        }
    }
}
