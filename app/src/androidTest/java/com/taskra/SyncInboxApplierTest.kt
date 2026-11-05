package com.taskra

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskra.data.*
import com.taskra.sync.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncInboxApplierTest {
    private val json = Json { encodeDefaults = true }

    private fun event(cursor: Long, kind: String, id: String, document: JsonElement): RemoteChange {
        val value = buildJsonObject {
            put("payload", json.encodeToJsonElement(SyncPayload(document)))
            put("attachments", JsonArray(emptyList()))
        }
        return RemoteChange(cursor, RemoteRecord(kind, id, cursor, "remote", cursor, value, true))
    }

    @Test fun preservesLocalEditsAndWaitsForMissingParents() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val sql = db.openHelper.writableDatabase
            SyncJournalSchema.install(sql)
            db.withTransaction { SyncJournalSchema.bindDevice(sql, "local") }
            sql.execSQL("UPDATE sync_control SET enabled=1 WHERE id=1")
            val h = Homework("h", "s", null, "远端作业")
            val document = HomeworkDocument(h, listOf(ContentBlock("b", "h", text = "远端正文")))
            val incoming = listOf(
                event(1, "homework", "h", json.encodeToJsonElement(document)),
                event(2, "semester", "s", json.encodeToJsonElement(Semester("s", "远端学期"))),
            )
            db.withTransaction { SyncJournalSchema.enqueueInbox(sql, "local", 0, incoming) }
            val applier = SyncInboxApplier(db)
            assertEquals(ApplyResult.WAITING_DEPENDENCY, applier.apply(1, "local"))
            assertNull(db.homeworkDao().get("h"))
            assertEquals(ApplyResult.APPLIED, applier.apply(2, "local"))
            assertEquals(ApplyResult.APPLIED, applier.apply(1, "local"))
            assertEquals("远端正文", db.blockDao().listByHomework("h").single().text)
            assertFalse(SyncJournalSchema.hasLocalPending(sql, "homework", "h"))

            db.homeworkDao().upsert(h.copy(title = "本机未上传修改"))
            val remoteEdit = event(3, "homework", "h", json.encodeToJsonElement(document.copy(homework = h.copy(title = "另一端修改"))))
            db.withTransaction { SyncJournalSchema.enqueueInbox(sql, "local", 2, listOf(remoteEdit)) }
            assertEquals(ApplyResult.WAITING_LOCAL, applier.apply(3, "local"))
            assertEquals("本机未上传修改", db.homeworkDao().get("h")!!.title)
            sql.query("SELECT COUNT(*) FROM sync_inbox WHERE cursor=3").use { check(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        } finally {
            db.close()
        }
    }

    @Test fun cloudDraftNeverOverwritesAnotherLocalNewDraft() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val sql = db.openHelper.writableDatabase
            SyncJournalSchema.install(sql)
            db.withTransaction { SyncJournalSchema.bindDevice(sql, "local") }
            sql.execSQL("UPDATE sync_control SET enabled=1 WHERE id=1")
            db.draftDao().upsert(EditorDraft("new", blocksJson = encodeBlocks(listOf(BlockJson("a", BlockKind.TEXT, "本机")))))
            val remoteDraft = EditorDraft("new", blocksJson = encodeBlocks(listOf(BlockJson("b", BlockKind.TEXT, "远端"))))
            db.withTransaction { SyncJournalSchema.enqueueInbox(sql, "local", 0, listOf(event(1, "draft", "new:remote", json.encodeToJsonElement(remoteDraft)))) }
            assertEquals(ApplyResult.APPLIED, SyncInboxApplier(db).apply(1, "local"))
            assertEquals("本机", decodeBlocks(db.draftDao().get("new")!!.blocksJson).single().text)
            assertEquals("远端", decodeBlocks(db.draftDao().get("new:remote")!!.blocksJson).single().text)
        } finally {
            db.close()
        }
    }
}
