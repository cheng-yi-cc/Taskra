package com.taskra

import android.content.ContextWrapper
import android.graphics.Bitmap
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
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncInboxAssetsTest {
    @Test fun installsVerifiedAssetsAndNeverOverwritesConflictingLocalFile() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
        val root = File(app.cacheDir, "incoming-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val sql = db.openHelper.writableDatabase
            SyncJournalSchema.install(sql)
            db.withTransaction { SyncJournalSchema.bindDevice(sql, "local") }
            sql.execSQL("UPDATE sync_control SET enabled=1 WHERE id=1")
            val incoming = PendingBlobStore(File(context.filesDir, "incoming"))
            val jpeg = File(root, "photo.jpg")
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            jpeg.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            bitmap.recycle()
            val recording = File(root, "recording.m4a").apply { writeText("recording bytes") }
            val imageHash = incoming.stage(jpeg).first
            val audioHash = incoming.stage(recording).first
            val image = ImageAsset("pic", "images/pic.jpg", 8, 8, jpeg.length())
            val assetList = listOf(SyncAsset("image", "pic", imageHash, image), SyncAsset("audio", "audio", audioHash))
            val doc = HomeworkDocument(Homework("h", "s", null, "远端作业"), listOf(
                ContentBlock("picture", "h", kind = BlockKind.IMAGE, position = 0, imageId = "pic"),
                ContentBlock("sound", "h", kind = BlockKind.AUDIO, position = 1, audioId = "audio"),
            ))
            val json = Json { encodeDefaults = true }
            fun event(cursor: Long, kind: String, id: String, docValue: JsonElement, refs: List<SyncAsset> = emptyList()): RemoteChange {
                val value = buildJsonObject {
                    put("payload", json.encodeToJsonElement(SyncPayload(docValue, refs)))
                    put("attachments", JsonArray(refs.map { JsonPrimitive(it.hash) }.distinct()))
                }
                return RemoteChange(cursor, RemoteRecord(kind, id, cursor, "remote", cursor, value, true))
            }
            db.withTransaction { SyncJournalSchema.enqueueInbox(sql, "local", 0, listOf(
                event(1, "semester", "s", json.encodeToJsonElement(Semester("s", "学期"))),
                event(2, "homework", "h", json.encodeToJsonElement(doc), assetList),
            )) }
            val applier = SyncInboxApplier(db, IncomingAssets(context, db, incoming))
            assertEquals(ApplyResult.APPLIED, applier.apply(1, "local"))
            assertEquals(ApplyResult.APPLIED, applier.apply(2, "local"))
            assertEquals(imageHash, syncFileSha256(File(context.filesDir, "images/pic.jpg")))
            assertEquals(audioHash, syncFileSha256(File(context.filesDir, "audio/audio.m4a")))
            assertEquals(listOf("picture", "sound"), db.blockDao().listByHomework("h").map { it.id })
            assertNotNull(db.imageDao().get("pic"))

            val conflicting = File(context.filesDir, "images/conflict.jpg").apply { writeText("existing local data") }
            val otherImage = ImageAsset("conflict", "images/conflict.jpg", 8, 8, jpeg.length())
            val otherDoc = HomeworkDocument(Homework("h2", "s", null, "冲突"), listOf(
                ContentBlock("conflict-block", "h2", kind = BlockKind.IMAGE, imageId = "conflict"),
            ))
            db.withTransaction { SyncJournalSchema.enqueueInbox(sql, "local", 2, listOf(
                event(3, "homework", "h2", json.encodeToJsonElement(otherDoc), listOf(SyncAsset("image", "conflict", imageHash, otherImage))),
            )) }
            assertEquals(ApplyResult.WAITING_CONFLICT, applier.apply(3, "local"))
            assertEquals("existing local data", conflicting.readText())
            assertNull(db.homeworkDao().get("h2"))
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }
}
