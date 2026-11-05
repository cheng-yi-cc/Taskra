package com.taskra

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskra.data.AppDatabase
import com.taskra.data.ContentBlock
import com.taskra.data.EditorDraft
import com.taskra.data.Homework
import com.taskra.data.Semester
import com.taskra.sync.SyncJournalSchema
import com.taskra.sync.RemoteChange
import com.taskra.sync.RemoteRecord
import com.taskra.sync.OutgoingChange
import com.taskra.sync.encodeOutgoingChange
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncJournalTest {
    @Test fun rejectedInitialSnapshotCanBeDiscardedOnlyAfterNewLocalEdit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val sql = db.openHelper.writableDatabase
            SyncJournalSchema.install(sql)
            db.semesterDao().upsert(Semester("s", "学期"))
            val homework = Homework("h", "s", null, "旧数据")
            db.homeworkDao().upsert(homework)
            db.withTransaction { SyncJournalSchema.bootstrap(sql, onlyIfAbsent = true) }
            val seq = sql.query("SELECT sequence FROM sync_dirty WHERE kind='homework' AND record_id='h'").use {
                check(it.moveToFirst()); it.getLong(0)
            }
            val encoded = encodeOutgoingChange(OutgoingChange("homework", "h", 1, seq, null, false, emptyList(), true))
            val observed = SyncJournalSchema.databaseSequence(sql)
            db.withTransaction { assertTrue(SyncJournalSchema.freeze(sql, "homework", "h", seq, observed, encoded)) }
            db.withTransaction { assertTrue(!SyncJournalSchema.discardRejectedInitialIfChanged(sql, "homework", "h", seq)) }
            db.homeworkDao().upsert(homework.copy(title = "用户新编辑"))
            db.withTransaction { assertTrue(SyncJournalSchema.discardRejectedInitialIfChanged(sql, "homework", "h", seq)) }
            assertTrue(SyncJournalSchema.hasLocalPending(sql, "homework", "h"))
            sql.query("SELECT COUNT(*) FROM sync_initial WHERE kind='homework' AND record_id='h'").use {
                check(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { db.close() }
    }

    @Test fun incomingCursorAndEventsCommitTogetherAndNewerHeadsAreNotDiscarded() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val sql = db.openHelper.writableDatabase
            SyncJournalSchema.install(sql)
            db.withTransaction { SyncJournalSchema.bindDevice(sql, "local_device") }
            fun event(cursor: Long) = RemoteChange(cursor, RemoteRecord("homework", "h", cursor, "other_device", cursor, JsonPrimitive("v$cursor"), true))
            db.withTransaction { assertTrue(SyncJournalSchema.enqueueInbox(sql, "local_device", 0, listOf(event(1), event(2)))) }
            db.withTransaction { assertTrue(!SyncJournalSchema.enqueueInbox(sql, "local_device", 0, listOf(event(1)))) }
            val invalid = runCatching {
                db.withTransaction { SyncJournalSchema.enqueueInbox(sql, "local_device", 2, listOf(event(4), event(3))) }
            }
            assertTrue(invalid.isFailure)
            sql.query("SELECT pull_cursor FROM sync_control WHERE id=1").use { check(it.moveToFirst()); assertEquals(2L, it.getLong(0)) }
            sql.query("SELECT COUNT(*) FROM sync_inbox").use { check(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
            val stale = runCatching { db.withTransaction { SyncJournalSchema.consumeInbox(sql, "homework", "h", "h", 1) } }
            assertTrue(stale.isFailure)
            sql.execSQL("UPDATE sync_control SET enabled=1 WHERE id=1")
            db.semesterDao().upsert(Semester("s", "学期"))
            db.homeworkDao().upsert(Homework("h", "s", null, "本机未上传"))
            val pending = runCatching { db.withTransaction { SyncJournalSchema.consumeInbox(sql, "homework", "h", "h", 2) } }
            assertTrue(pending.isFailure)
            sql.execSQL("DELETE FROM sync_dirty WHERE kind='homework' AND record_id='h'")
            db.withTransaction { SyncJournalSchema.consumeInbox(sql, "homework", "h", "h", 2) }
            sql.query("SELECT COUNT(*) FROM sync_inbox").use { check(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        } finally {
            db.close()
        }
    }

    @Test fun backupStyleClearDoesNotErasePendingSyncMetadata() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val sql = db.openHelper.writableDatabase
            SyncJournalSchema.install(sql)
            sql.execSQL("UPDATE sync_control SET enabled=1 WHERE id=1")
            db.semesterDao().upsert(Semester("s", "学期"))
            db.homeworkDao().upsert(Homework("h", "s", null, "作业"))
            val version = sql.query("SELECT sequence FROM sync_dirty WHERE kind='homework' AND record_id='h'").use {
                check(it.moveToFirst()); it.getLong(0)
            }
            val databaseSequence = SyncJournalSchema.databaseSequence(sql)
            db.withTransaction { assertTrue(SyncJournalSchema.freeze(sql, "homework", "h", version, databaseSequence, "待发送")) }
            db.clearAllTables()
            sql.query("SELECT enabled FROM sync_control WHERE id=1").use { check(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            sql.query("SELECT COUNT(*) FROM sync_outbox").use { check(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            sql.query("SELECT deleted FROM sync_dirty WHERE kind='homework' AND record_id='h'").use {
                check(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    @Test fun bootstrapIncludesExistingDataOnceWithoutOverwritingNewerPendingChange() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.semesterDao().upsert(Semester("s", "学期"))
            db.homeworkDao().upsert(Homework("old", "s", null, "已有作业"))
            val sql = db.openHelper.writableDatabase
            SyncJournalSchema.install(sql)
            sql.execSQL("UPDATE sync_control SET enabled=1 WHERE id=1")
            db.homeworkDao().upsert(Homework("new", "s", null, "后续作业"))
            val before = sql.query("SELECT sequence FROM sync_dirty WHERE kind='homework' AND record_id='new'").use {
                check(it.moveToFirst()); it.getLong(0)
            }
            val failure = runCatching { db.withTransaction { SyncJournalSchema.bootstrap(sql); error("rollback") } }
            assertTrue(failure.isFailure)
            sql.query("SELECT bootstrap_complete FROM sync_control WHERE id=1").use {
                check(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            db.withTransaction { SyncJournalSchema.bootstrap(sql) }
            db.withTransaction { SyncJournalSchema.bootstrap(sql) }
            sql.query("SELECT record_id, sequence FROM sync_dirty WHERE kind='homework' ORDER BY record_id").use {
                assertTrue(it.moveToFirst())
                assertEquals("new", it.getString(0))
                assertEquals(before, it.getLong(1))
                assertTrue(it.moveToNext())
                assertEquals("old", it.getString(0))
                assertTrue(!it.moveToNext())
            }
        } finally {
            db.close()
        }
    }

    @Test fun journalIsAtomicAndHardDeleteKeepsTombstone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val sql = db.openHelper.writableDatabase
            SyncJournalSchema.install(sql)
            sql.execSQL("UPDATE sync_control SET enabled=1 WHERE id=1")
            db.semesterDao().upsert(Semester("s", "学期"))
            val homework = Homework("h", "s", null, "原作业")
            db.homeworkDao().upsert(homework)
            db.blockDao().upsertAll(listOf(ContentBlock("b", "h", text = "内容")))
            db.draftDao().upsert(EditorDraft("new", blocksJson = "[]"))
            db.draftDao().upsert(EditorDraft("new:remote_device", blocksJson = "[]"))
            sql.query("SELECT COUNT(*) FROM sync_dirty WHERE kind='draft' AND deleted=0").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }

            fun version(): Pair<Long, Int> = sql.query(
                "SELECT sequence, deleted FROM sync_dirty WHERE kind='homework' AND record_id='h'"
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0) to cursor.getInt(1)
            }
            val before = version()
            assertTrue(before.first > 0)
            assertEquals(0, before.second)
            val premature = runCatching {
                db.withTransaction { SyncJournalSchema.acknowledge(sql, "homework", "h", before.first) }
            }
            assertTrue(premature.isFailure)
            assertEquals(before, version())
            val databaseSequence = SyncJournalSchema.databaseSequence(sql)
            db.withTransaction {
                assertTrue(SyncJournalSchema.freeze(sql, "homework", "h", before.first, databaseSequence, "原始快照"))
                assertTrue(SyncJournalSchema.freeze(sql, "homework", "h", before.first, databaseSequence, "原始快照"))
            }
            val inconsistent = runCatching {
                db.withTransaction { SyncJournalSchema.freeze(sql, "homework", "h", before.first, databaseSequence, "篡改快照") }
            }
            assertTrue(inconsistent.isFailure)

            val failure = runCatching {
                db.withTransaction {
                    db.homeworkDao().upsert(homework.copy(title = "不应提交"))
                    error("rollback")
                }
            }
            assertTrue(failure.isFailure)
            assertEquals("原作业", db.homeworkDao().get("h")!!.title)
            assertEquals(before, version())

            db.withTransaction {
                sql.execSQL("UPDATE sync_control SET applying_remote=1 WHERE id=1")
                db.homeworkDao().upsert(homework.copy(title = "远端版本"))
                sql.execSQL("UPDATE sync_control SET applying_remote=0 WHERE id=1")
            }
            assertEquals(before, version())
            db.homeworkDao().upsert(homework.copy(title = "上传期间的新修改"))
            val afterEdit = version()
            assertTrue(afterEdit.first > before.first)
            db.withTransaction { SyncJournalSchema.acknowledge(sql, "homework", "h", before.first) }
            assertEquals(afterEdit, version())
            db.withTransaction {
                assertTrue(!SyncJournalSchema.freeze(sql, "homework", "h", before.first, databaseSequence, "过期快照"))
            }
            val beforeOtherChange = SyncJournalSchema.databaseSequence(sql)
            db.semesterDao().upsert(Semester("s", "学期已修改"))
            db.withTransaction {
                assertTrue(!SyncJournalSchema.freeze(sql, "homework", "h", afterEdit.first, beforeOtherChange, "不一致快照"))
            }
            db.homeworkDao().deleteHard("h")
            assertEquals(1, version().second)
            assertTrue(version().first > afterEdit.first)
            assertTrue(db.blockDao().listByHomework("h").isEmpty())
        } finally {
            db.close()
        }
    }
}
