package com.taskra

import android.graphics.Bitmap
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskra.data.*
import com.taskra.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 仅在专用空白模拟器运行：先用 v5 安装包写入，再覆盖安装 v6 包验证。 */
@RunWith(AndroidJUnit4::class)
class SyncUpgradeFixture {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
    private val image get() = File(app.filesDir, "images/sync-fixture.jpg")
    private val audio get() = File(app.filesDir, "audio/sync-fixture.m4a")
    private val marker get() = File(app.filesDir, "sync-upgrade-fixture.txt")

    @Test fun seedVersionFive() = runBlocking {
        assertEquals(5, app.db.openHelper.writableDatabase.version)
        image.parentFile!!.mkdirs()
        audio.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        bitmap.recycle()
        InstrumentationRegistry.getInstrumentation().context.assets.open("template-test.m4a").use { input ->
            audio.outputStream().use { input.copyTo(it) }
        }
        val oldCreatedAt = 1_601_234_567_890L
        app.db.withTransaction {
            app.db.semesterDao().upsert(Semester("sync-upgrade-sem", "升级前学期"))
            app.db.courseDao().upsert(Course("sync-upgrade-course", "sync-upgrade-sem", "升级前课程"))
            app.db.homeworkDao().upsert(Homework("sync-upgrade-homework", "sync-upgrade-sem", "sync-upgrade-course", "升级前作业", createdAt = oldCreatedAt))
            app.db.imageDao().upsert(ImageAsset("sync-fixture", "images/sync-fixture.jpg", 8, 8, image.length()))
            app.db.blockDao().upsertAll(listOf(
                ContentBlock("sync-upgrade-text", "sync-upgrade-homework", position = 0, text = "升级前正文"),
                ContentBlock("sync-upgrade-image", "sync-upgrade-homework", kind = BlockKind.IMAGE, position = 1, imageId = "sync-fixture"),
                ContentBlock("sync-upgrade-audio", "sync-upgrade-homework", kind = BlockKind.AUDIO, position = 2, audioId = "sync-fixture", audioDurationMs = 2000),
            ))
            app.db.draftDao().upsert(EditorDraft("new:sync_fixture", semesterId = "sync-upgrade-sem", courseId = "sync-upgrade-course", blocksJson = encodeBlocks(listOf(BlockJson("fixture-draft", BlockKind.TEXT, "升级前草稿")))))
        }
        app.prefs.setMotto("升级前自励文字")
        marker.writeText("${sha256(image)}\n${sha256(audio)}\n")
    }

    @Test fun verifyAfterUpgrade() = runBlocking {
        assertEquals(6, app.db.openHelper.writableDatabase.version)
        val hashes = marker.readLines()
        assertEquals(hashes[0], sha256(image))
        assertEquals(hashes[1], sha256(audio))
        val item = app.repo.detail("sync-upgrade-homework")!!
        assertEquals("升级前作业", item.homework.title)
        assertEquals(1_601_234_567_890L, item.homework.createdAt)
        assertEquals("sync-upgrade-course", item.homework.courseId)
        assertEquals(listOf("sync-upgrade-text", "sync-upgrade-image", "sync-upgrade-audio"), item.question.map { it.id })
        assertEquals("升级前正文", item.question.first().text)
        assertEquals("升级前草稿", decodeBlocks(app.repo.getDraft("new:sync_fixture")!!.blocksJson).single().text)
        assertEquals("升级前自励文字", app.prefs.snapshot().mottoText)
        app.db.openHelper.readableDatabase.query("PRAGMA integrity_check").use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
        }
        app.db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun seedPendingVersionSix() = runBlocking {
        assertEquals(6, app.db.openHelper.writableDatabase.version)
        val old = requireNotNull(app.db.homeworkDao().get("sync-upgrade-homework"))
        app.db.homeworkDao().upsert(old.copy(title = "升级前待发送作业"))
        val binding = app.db.openHelper.readableDatabase.query("SELECT binding FROM sync_control WHERE id=1").use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        assertTrue(binding.isNotEmpty())
        val pending = PendingBlobStore(File(app.filesDir, "sync-outbox/blobs"))
        assertTrue(SyncSnapshotter(app, app.db, pending).freeze("homework", old.id, binding) { AssessedTime(it, false) })
        val payload = app.db.openHelper.readableDatabase.query("SELECT payload FROM sync_outbox WHERE kind='homework' AND record_id='sync-upgrade-homework'").use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        File(app.filesDir, "sync-pending-fixture.txt").writeText(sha256Bytes(payload.toByteArray()))
    }

    @Test fun verifyPendingVersionSix() = runBlocking {
        assertEquals(6, app.db.openHelper.writableDatabase.version)
        assertEquals("升级前待发送作业", app.db.homeworkDao().get("sync-upgrade-homework")!!.title)
        val payload = app.db.openHelper.readableDatabase.query("SELECT payload FROM sync_outbox WHERE kind='homework' AND record_id='sync-upgrade-homework'").use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        assertEquals(File(app.filesDir, "sync-pending-fixture.txt").readText(), sha256Bytes(payload.toByteArray()))
        val change = decodeOutgoingChange(payload)
        change.attachments.forEach { PendingBlobStore(File(app.filesDir, "sync-outbox/blobs")).get(it) }
        app.db.openHelper.readableDatabase.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
    }
}
