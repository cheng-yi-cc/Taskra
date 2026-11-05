package com.taskra

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskra.data.*
import com.taskra.sync.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncSnapshotterTest {
    @Test fun freezesCompleteHomeworkAndAttachmentsAndRejectsMissingFile() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
        val root = File(app.cacheDir, "snapshot-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val sql = db.openHelper.writableDatabase
            SyncJournalSchema.install(sql)
            sql.execSQL("UPDATE sync_control SET enabled=1 WHERE id=1")
            db.semesterDao().upsert(Semester("s", "学期"))
            val image = File(context.filesDir, "images/pic.jpg").apply { parentFile!!.mkdirs() }
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            bitmap.recycle()
            val audio = File(context.filesDir, "audio/voice.m4a").apply { parentFile!!.mkdirs(); writeText("audio bytes") }
            db.imageDao().upsert(ImageAsset("pic", "images/pic.jpg", 8, 8, image.length()))
            db.homeworkDao().upsert(Homework("h", "s", null, "作业"))
            db.blockDao().upsertAll(listOf(
                ContentBlock("text", "h", position = 0, text = "正文"),
                ContentBlock("image", "h", kind = BlockKind.IMAGE, position = 1, imageId = "pic"),
                ContentBlock("audio", "h", kind = BlockKind.AUDIO, position = 2, audioId = "voice"),
            ))
            val blobStore = PendingBlobStore(File(context.filesDir, "pending"))
            val snapshotter = SyncSnapshotter(context, db, blobStore)
            assertTrue(snapshotter.freeze("homework", "h", "device") { AssessedTime(it, false) })
            val encoded = sql.query("SELECT payload FROM sync_outbox WHERE kind='homework' AND record_id='h'").use {
                check(it.moveToFirst()); it.getString(0)
            }
            val outgoing = decodeOutgoingChange(encoded)
            assertEquals(2, outgoing.attachments.size)
            val payload = Json.decodeFromJsonElement<SyncPayload>(outgoing.value!!)
            val document = Json.decodeFromJsonElement<HomeworkDocument>(payload.document)
            assertEquals(listOf("text", "image", "audio"), document.blocks.map { it.id })
            image.delete()
            audio.delete()
            assertTrue(outgoing.attachments.all { blobStore.get(it).isFile })

            db.homeworkDao().upsert(Homework("missing", "s", null, "缺失录音"))
            db.blockDao().upsertAll(listOf(ContentBlock("missing-audio", "missing", kind = BlockKind.AUDIO, audioId = "absent")))
            assertTrue(runCatching { snapshotter.freeze("homework", "missing", "device") { AssessedTime(it, false) } }.isFailure)
            sql.query("SELECT COUNT(*) FROM sync_outbox WHERE record_id='missing'").use {
                check(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }
}
