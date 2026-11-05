package com.taskra

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskra.data.AppDatabase
import com.taskra.data.Homework
import com.taskra.data.Semester
import com.taskra.sync.SyncCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** 在隔离模拟器中测试完整协调器、加密凭据以及持续运行时的自动收发。 */
@RunWith(AndroidJUnit4::class)
class SyncCoordinatorIntegrationTest {
    @Test fun pairingAndAutomaticSynchronizationAcrossDevices() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
        val cert = File(app.filesDir, "taskra-test-cert.pem")
        val code = File(app.filesDir, "taskra-test-code.txt")
        assumeTrue(cert.isFile && code.isFile)
        val root = File(app.cacheDir, "coordinator-test-${UUID.randomUUID()}").apply { mkdirs() }
        fun isolated(name: String): Context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = File(root, name).apply { mkdirs() }
            override fun getSharedPreferences(key: String, mode: Int) =
                super.getSharedPreferences("${root.name}_${name}_$key", mode)
        }
        val original = HttpsURLConnection.getDefaultSSLSocketFactory()
        val phone = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val tablet = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        var phoneSync: SyncCoordinator? = null
        var tabletSync: SyncCoordinator? = null
        try {
            val certificate = cert.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("test", certificate) }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply {
                init(null, managers.trustManagers, SecureRandom())
            }.socketFactory)
            // 内存数据库不走应用数据库的 onOpen 回调，因此显式安装实验日志。
            com.taskra.sync.SyncJournalSchema.install(phone.openHelper.writableDatabase)
            com.taskra.sync.SyncJournalSchema.install(tablet.openHelper.writableDatabase)
            phone.semesterDao().upsert(Semester("semester", "测试学期"))
            phone.homeworkDao().upsert(Homework("homework", "semester", null, "手机内容"))
            phoneSync = SyncCoordinator(isolated("phone"), phone)
            phoneSync.connect("https://127.0.0.1:38443", code.readText().trim())
            val tabletCode = phoneSync.createPairCode().code
            tabletSync = SyncCoordinator(isolated("tablet"), tablet)
            tabletSync.connect("https://127.0.0.1:38443", tabletCode)
            tabletSync.syncNow()
            assertEquals("手机内容", tablet.homeworkDao().get("homework")?.title)
            phoneSync.syncNow()
            // 等首次配对引起的本机唤醒消退；随后对端写入不应依赖本机再次编辑。
            delay(4_000)
            tablet.homeworkDao().upsert(Homework("from-tablet", "semester", null, "平板自动同步"))
            withTimeout(50_000) {
                while (phone.homeworkDao().get("from-tablet") == null) delay(250)
            }
            assertEquals("平板自动同步", phone.homeworkDao().get("from-tablet")?.title)
        } finally {
            phoneSync?.close(); tabletSync?.close()
            HttpsURLConnection.setDefaultSSLSocketFactory(original)
            phone.close(); tablet.close(); root.deleteRecursively()
        }
    }
}
