package com.taskra

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskra.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class AudioStorageTest {
    @Test fun backupRefusesMissingImageInsteadOfCreatingAnIncompleteArchive() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
        val root = File(app.cacheDir, "missing-image-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.imageDao().upsert(ImageAsset("missing", "images/missing.jpg", 1, 1, 1))
            val manager = BackupManager(context, TaskraRepository(db, context.filesDir), app.prefs)
            assertTrue(runCatching { manager.exportTo(Uri.fromFile(File(root, "backup.zip"))) }.isFailure)
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun migration_preservesExistingTextAndFormatting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null).callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE content_blocks (id TEXT NOT NULL PRIMARY KEY, text TEXT, spanJson TEXT NOT NULL)")
                    db.execSQL("INSERT INTO content_blocks VALUES ('old', '原来的题目', '[{\"type\":\"BOLD\",\"start\":0,\"end\":2}]')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build())
        try {
            val db = helper.writableDatabase
            MIGRATION_2_3.migrate(db)
            db.query("SELECT text, spanJson, audioId, audioDurationMs FROM content_blocks").use {
                assertTrue(it.moveToFirst())
                assertEquals("原来的题目", it.getString(0))
                assertTrue(it.getString(1).contains("BOLD"))
                assertTrue(it.isNull(2))
                assertEquals(0L, it.getLong(3))
            }
        } finally { helper.close() }
    }

    @Test fun audio_backup_restore_and_referenceSafeDeletion() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
        val root = File(app.cacheDir, "audio-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repo = TaskraRepository(db, context.filesDir)
        val manager = BackupManager(context, repo, app.prefs)
        val oldPrefs = app.prefs.snapshot()
        try {
            repo.saveSemester(Semester("semester", "测试学期"))
            val audio = audioFile(context, "sample").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val block = ContentBlock("audio-block", "", kind = BlockKind.AUDIO, audioId = "sample", audioDurationMs = 2500)
            val id = repo.createHomework("semester", null, "录音作业", "NONE", null, null, null, listOf(block), emptyList())
            repo.saveDraft(EditorDraft("new", blocksJson = encodeBlocks(listOf(BlockJson("draft-audio", BlockKind.AUDIO, audioId = "sample", audioDurationMs = 2500)))))
            val backup = File(root, "backup.zip")
            manager.exportTo(Uri.fromFile(backup))
            ZipFile(backup).use { assertNotNull(it.getEntry("audio/sample.m4a")) }
            assertEquals(1, manager.inspectBackup(Uri.fromFile(backup)).audioCount)
            audio.writeBytes(byteArrayOf(9))
            manager.restoreFrom(Uri.fromFile(backup), true, ImageStore(context, db))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), audio.readBytes())
            assertEquals(2500L, repo.detail(id)!!.question.single().audioDurationMs)
            repo.deleteForever(id, ImageStore(context, db))
            assertTrue("草稿仍引用录音", audio.exists())
            repo.deleteDraft("new")
            assertFalse(audio.exists())
        } finally { app.prefs.restore(oldPrefs); db.close(); root.deleteRecursively() }
    }
}
