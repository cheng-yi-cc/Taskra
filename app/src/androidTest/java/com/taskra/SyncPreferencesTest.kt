package com.taskra

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskra.data.*
import com.taskra.sync.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncPreferencesTest {
    @Test fun joiningAnExistingServerDoesNotUploadDeviceDefaults() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val sql = db.openHelper.writableDatabase
            SyncJournalSchema.install(sql)
            db.withTransaction { SyncJournalSchema.bindDevice(sql, "tablet"); SyncJournalSchema.bootstrap(sql) }
            SyncPreferences(db, app.prefs).reconcile(true)
            assertFalse(db.withTransaction { SyncJournalSchema.hasLocalPending(sql, "preferences", "settings") })
        } finally { db.close() }
    }

    @Test fun localEditsRemoteApplyAndInterruptedApplyRemainRecoverable() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val old = app.prefs.snapshot()
        val codec = Json { encodeDefaults = true }
        val sql = db.openHelper.writableDatabase
        val bridge = SyncPreferences(db, app.prefs)
        fun event(cursor: Long, value: UserPrefs): RemoteChange {
            val payload = SyncPayload(codec.encodeToJsonElement(value.copy(lastBackupTime = 0)))
            val wrapped = buildJsonObject {
                put("payload", codec.encodeToJsonElement(payload))
                put("attachments", buildJsonArray {})
            }
            return RemoteChange(cursor, RemoteRecord("preferences", "settings", cursor, "other", cursor, wrapped, true))
        }
        try {
            SyncJournalSchema.install(sql)
            db.withTransaction { SyncJournalSchema.bindDevice(sql, "local"); SyncJournalSchema.bootstrap(sql) }
            app.prefs.setLastBackupTime(12345)
            app.prefs.setMotto("本机")
            bridge.reconcile(false)
            assertTrue(db.withTransaction { SyncJournalSchema.hasLocalPending(sql, "preferences", "settings") })
            // 模拟发送成功，保留客户端实际使用的待确认日志语义。
            db.withTransaction {
                val seq = sql.query("SELECT sequence FROM sync_dirty WHERE kind='preferences'").use { assertTrue(it.moveToFirst()); it.getLong(0) }
                sql.execSQL("INSERT INTO sync_outbox VALUES ('preferences','settings',?, '{}')", arrayOf(seq))
                SyncJournalSchema.acknowledge(sql, "preferences", "settings", seq)
            }
            val remote = app.prefs.snapshot().copy(mottoText = "远端", listOrders = mapOf("todo" to listOf("b", "a")), lastBackupTime = 0)
            db.withTransaction { assertTrue(SyncJournalSchema.enqueueInbox(sql, "local", 0, listOf(event(1, remote)))) }
            assertEquals(ApplyResult.APPLIED, bridge.apply(1))
            assertEquals("远端", app.prefs.snapshot().mottoText)
            assertEquals(listOf("b", "a"), app.prefs.snapshot().listOrders["todo"])
            assertEquals(12345L, app.prefs.snapshot().lastBackupTime)

            val afterCrash = remote.copy(mottoText = "中断后恢复")
            db.withTransaction {
                assertTrue(SyncJournalSchema.enqueueInbox(sql, "local", 1, listOf(event(2, afterCrash))))
                sql.execSQL("UPDATE sync_preferences SET apply_cursor=2,apply_target=? WHERE id=1", arrayOf(codec.encodeToString(afterCrash)))
            }
            SyncGates.preferences.withLock { app.prefs.applySynchronized(afterCrash) }
            bridge.reconcile(true)
            assertEquals("中断后恢复", app.prefs.snapshot().mottoText)
            assertFalse(db.withTransaction { SyncJournalSchema.hasLocalPending(sql, "preferences", "settings") })
            assertEquals(0L, sql.query("SELECT COUNT(*) FROM sync_inbox").use { assertTrue(it.moveToFirst()); it.getLong(0) })

            app.prefs.setMotto("离线编辑")
            val laterRemote = afterCrash.copy(mottoText = "另一台设备")
            db.withTransaction { assertTrue(SyncJournalSchema.enqueueInbox(sql, "local", 2, listOf(event(3, laterRemote)))) }
            assertEquals(ApplyResult.WAITING_LOCAL, bridge.apply(3))
            assertEquals("离线编辑", app.prefs.snapshot().mottoText)
            assertTrue(db.withTransaction { SyncJournalSchema.hasLocalPending(sql, "preferences", "settings") })
        } finally { app.prefs.restore(old); db.close() }
    }
}
