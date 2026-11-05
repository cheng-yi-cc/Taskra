package com.taskra

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskra.sync.PendingBlobStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PendingBlobStoreTest {
    @Test fun stagedAttachmentSurvivesSourceDeletionAndDetectsCorruption() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "pending-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "source").apply { writeText("原始录音内容") }
            val store = PendingBlobStore(File(root, "outbox"))
            val (hash, staged) = store.stage(source)
            assertEquals(hash, store.stage(source).first)
            source.delete()
            assertArrayEquals("原始录音内容".toByteArray(), store.get(hash).readBytes())
            staged.writeText("损坏")
            assertThrows(IllegalArgumentException::class.java) { store.get(hash) }
        } finally {
            root.deleteRecursively()
        }
    }
}
