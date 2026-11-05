package com.taskra

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskra.data.*
import com.taskra.sync.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** 测试证书仅注入当前隔离模拟器进程；生产客户端仍使用系统 HTTPS 信任链。 */
@RunWith(AndroidJUnit4::class)
class SyncTlsIntegrationTest {
    @Test fun realHttpsServicePairsAndSynchronizesTwoIsolatedDatabases() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
        val certFile = File(app.filesDir, "taskra-test-cert.pem")
        val codeFile = File(app.filesDir, "taskra-test-code.txt")
        assumeTrue(certFile.isFile && codeFile.isFile)
        val endpoint = "https://127.0.0.1:38443"
        val originalFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val root = File(app.cacheDir, "tls-sync-test-${UUID.randomUUID()}").apply { mkdirs() }
        fun isolated(name: String) = object : ContextWrapper(app) { override fun getFilesDir(): File = File(root, name).apply { mkdirs() } }
        val phoneContext = isolated("phone")
        val tabletContext = isolated("tablet")
        val phone = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val tablet = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            assertTrue(runCatching { SyncClient(endpoint, "invalid").serverTime() }.isFailure)
            val certificate = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("test", certificate) }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            val tls = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, SecureRandom()) }
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            val code = codeFile.readText().trim()
            val requestId = "pair_request_1234567890"
            val phoneDevice = SyncClient(endpoint).redeem(code, requestId)
            assertEquals(phoneDevice, SyncClient(endpoint).redeem(code, requestId))
            assertEquals(401, (runCatching { SyncClient(endpoint).redeem(code) }.exceptionOrNull() as SyncRequestException).status)
            assertEquals(401, (runCatching { SyncClient(endpoint, "wrong-token").serverTime() }.exceptionOrNull() as SyncRequestException).status)
            val phoneClient = SyncClient(endpoint, phoneDevice.token)
            assertTrue(phoneClient.serverTime() > 0)
            if (File(app.filesDir, "taskra-test-perf.flag").isFile) {
                // 8 个分块、每次请求注入 150 ms 延迟；前半段模拟旧的串行上传。
                val serialData = ByteArray(8 * 65536) { ((it * 7) % 251).toByte() }
                val serialHash = java.security.MessageDigest.getInstance("SHA-256").digest(serialData).joinToString("") { "%02x".format(it) }
                val serialStart = SystemClock.elapsedRealtime()
                repeat(8) { index ->
                    val partConnection = URI("$endpoint/v1/blobs/$serialHash/parts/$index").toURL().openConnection() as HttpsURLConnection
                    try {
                        partConnection.requestMethod = "PUT"
                        partConnection.setRequestProperty("Authorization", "Bearer ${phoneDevice.token}")
                        partConnection.doOutput = true
                        partConnection.setFixedLengthStreamingMode(65536)
                        partConnection.outputStream.use { it.write(serialData, index * 65536, 65536) }
                        assertEquals(200, partConnection.responseCode)
                    } finally { partConnection.disconnect() }
                }
                val serialMs = SystemClock.elapsedRealtime() - serialStart
                val parallelFile = File(root, "parallel-upload.bin").apply {
                    writeBytes(ByteArray(8 * 65536) { ((it * 11 + 3) % 251).toByte() })
                }
                val parallelStart = SystemClock.elapsedRealtime()
                phoneClient.upload(syncFileSha256(parallelFile), parallelFile)
                val parallelMs = SystemClock.elapsedRealtime() - parallelStart
                Log.i("TaskraSyncPerf", "synthetic 512KiB: serial=${serialMs}ms parallel=${parallelMs}ms")
                assertTrue("并行分块应减少高延迟网络的等待", parallelMs * 10 < serialMs * 9)
            }
            // 先留下一个已确认分块，模拟进程中断，再通过真实客户端续传并校验完整内容。
            val large = File(root, "resumable.bin").apply { writeBytes(ByteArray(150_000) { (it % 251).toByte() }) }
            val largeHash = syncFileSha256(large)
            val part = URI("$endpoint/v1/blobs/$largeHash/parts/0").toURL().openConnection() as HttpsURLConnection
            try {
                part.requestMethod = "PUT"
                part.setRequestProperty("Authorization", "Bearer ${phoneDevice.token}")
                part.doOutput = true
                part.setFixedLengthStreamingMode(65536)
                part.outputStream.use { out -> out.write(large.readBytes(), 0, 65536) }
                assertEquals(200, part.responseCode)
            } finally { part.disconnect() }
            phoneClient.upload(largeHash, large)
            val received = File(File(root, "received"), largeHash)
            received.parentFile!!.mkdirs()
            // 模拟已持久化的部分下载；完整内容仍必须通过哈希校验。
            File(received.parentFile, ".$largeHash.download").writeBytes(large.readBytes().copyOf(65536))
            phoneClient.download(largeHash, received)
            assertEquals(largeHash, syncFileSha256(received))
            val retryDestination = File(File(root, "retry"), largeHash)
            retryDestination.parentFile!!.mkdirs()
            File(retryDestination.parentFile, ".$largeHash.download").writeBytes(ByteArray(1000) { 7 })
            assertTrue(runCatching { phoneClient.download(largeHash, retryDestination) }.isFailure)
            assertFalse(retryDestination.exists())
            phoneClient.download(largeHash, retryDestination)
            assertEquals(largeHash, syncFileSha256(retryDestination))
            val phoneSql = phone.openHelper.writableDatabase
            val tabletSql = tablet.openHelper.writableDatabase
            SyncJournalSchema.install(phoneSql)
            SyncJournalSchema.install(tabletSql)
            phone.withTransaction { SyncJournalSchema.bindDevice(phoneSql, phoneDevice.deviceId) }
            val imageFile = File(phoneContext.filesDir, "images/pic.jpg").apply { parentFile!!.mkdirs() }
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            imageFile.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            bitmap.recycle()
            val audioFile = File(phoneContext.filesDir, "audio/voice.m4a").apply { parentFile!!.mkdirs(); writeText("synthetic recording") }
            phone.semesterDao().upsert(Semester("s", "学期"))
            phone.courseDao().upsert(Course("c", "s", "课程"))
            phone.imageDao().upsert(ImageAsset("pic", "images/pic.jpg", 8, 8, imageFile.length()))
            phone.homeworkDao().upsert(Homework("h", "s", "c", "手机作业"))
            phone.blockDao().upsertAll(listOf(
                ContentBlock("text", "h", position = 0, text = "题目"),
                ContentBlock("pic-block", "h", kind = BlockKind.IMAGE, position = 1, imageId = "pic"),
                ContentBlock("audio-block", "h", kind = BlockKind.AUDIO, position = 2, audioId = "voice"),
            ))
            phone.draftDao().upsert(EditorDraft("new", blocksJson = encodeBlocks(listOf(BlockJson("draft", BlockKind.TEXT, "手机草稿")))))
            phone.withTransaction { SyncJournalSchema.bootstrap(phoneSql, onlyIfAbsent = true) }
            val assess: (Long) -> AssessedTime = { AssessedTime(it, true) }
            val phoneEngine = SyncEngine(phoneContext, phone, phoneDevice.deviceId, phoneClient)
            assertFalse(phoneEngine.runOnce(assess).pending)
            val tabletDevice = SyncClient(endpoint).redeem(phoneClient.issuePairCode().code)
            val tabletClient = SyncClient(endpoint, tabletDevice.token)
            tablet.withTransaction {
                SyncJournalSchema.bindDevice(tabletSql, tabletDevice.deviceId)
                SyncJournalSchema.bootstrap(tabletSql, onlyIfAbsent = true)
            }
            val tabletEngine = SyncEngine(tabletContext, tablet, tabletDevice.deviceId, tabletClient)
            assertFalse(tabletEngine.runOnce(assess).pending)
            assertEquals("手机作业", tablet.homeworkDao().get("h")!!.title)
            assertEquals(syncFileSha256(imageFile), syncFileSha256(File(tabletContext.filesDir, "images/pic.jpg")))
            assertEquals(syncFileSha256(audioFile), syncFileSha256(File(tabletContext.filesDir, "audio/voice.m4a")))
            assertNotNull(tablet.draftDao().get("new:${phoneDevice.deviceId}"))
            tablet.homeworkDao().upsert(Homework("tablet", "s", "c", "平板新增"))
            assertFalse(tabletEngine.runOnce(assess).pending)
            assertFalse(phoneEngine.runOnce(assess).pending)
            assertEquals("平板新增", phone.homeworkDao().get("tablet")!!.title)
            val collision = OutgoingChange("homework", "h", System.currentTimeMillis(), 999_999, JsonPrimitive("old data"), true, emptyList(), true)
            assertEquals(409, (runCatching { tabletClient.push(listOf(collision)) }.exceptionOrNull() as SyncRequestException).status)
            assertEquals("手机作业", phone.homeworkDao().get("h")!!.title)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(originalFactory)
            phone.close(); tablet.close(); root.deleteRecursively()
        }
    }
}
