package com.taskra

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskra.sync.LAN_SYNC_PORT
import com.taskra.sync.LAN_TLS_HOST
import com.taskra.sync.SyncClient
import com.taskra.sync.SyncRequestException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** 测试服务仅含合成数据；Android 模拟器通过 10.0.2.2 访问电脑。 */
@RunWith(AndroidJUnit4::class)
class SyncLanIntegrationTest {
    @Test fun lanRejectsTrustedCertificateForWrongHostname() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
        val cert = File(app.filesDir, "taskra-lan-wrong-test-cert.pem")
        assumeTrue(cert.isFile)
        val old = HttpsURLConnection.getDefaultSSLSocketFactory()
        try {
            val certificate = cert.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("test", certificate) }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply {
                init(null, managers.trustManagers, SecureRandom())
            }.socketFactory)
            val endpoint = "https://$LAN_TLS_HOST:$LAN_SYNC_PORT/taskra-sync"
            val failure = runCatching { SyncClient(endpoint, "invalid", lanProxyAddress = "10.0.2.2").serverTime() }.exceptionOrNull()
            assertTrue(failure is javax.net.ssl.SSLException)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(old)
        }
    }

    @Test fun lanConnectUsesRealTlsHostnameAndAuthenticatedApi() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
        val cert = File(app.filesDir, "taskra-lan-test-cert.pem")
        val code = File(app.filesDir, "taskra-lan-test-code.txt")
        assumeTrue(cert.isFile && code.isFile)
        val old = HttpsURLConnection.getDefaultSSLSocketFactory()
        try {
            val certificate = cert.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("test", certificate) }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply {
                init(null, managers.trustManagers, SecureRandom())
            }.socketFactory)
            val endpoint = "https://$LAN_TLS_HOST:$LAN_SYNC_PORT/taskra-sync"
            val initial = SyncClient(endpoint, lanProxyAddress = "10.0.2.2")
            val device = initial.redeem(code.readText().trim())
            val paired = SyncClient(endpoint, device.token, lanProxyAddress = "10.0.2.2")
            assertTrue(paired.serverTime() > 0)
            val wrong = SyncClient(endpoint, "invalid", lanProxyAddress = "10.0.2.2")
            assertEquals(401, (runCatching { wrong.serverTime() }.exceptionOrNull() as SyncRequestException).status)
            assertTrue(runCatching { SyncClient(endpoint, device.token, lanProxyAddress = "1.1.1.1").serverTime() }.isFailure)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(old)
        }
    }
}
