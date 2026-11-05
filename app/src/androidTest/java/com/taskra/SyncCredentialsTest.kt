package com.taskra

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskra.sync.SyncCredentialStore
import com.taskra.sync.SyncCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncCredentialsTest {
    @Test fun encryptsCredentialsAndDetectsTampering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "test_sync_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
        val store = SyncCredentialStore(context, namespace)
        val value = SyncCredentials("https://example.test/sync", "device-id", "secret-token")
        try {
            store.save(value)
            assertEquals(value, store.load())
            assertFalse(prefs.getString("sealed", "")!!.contains(value.token))
            assertEquals(true, prefs.edit().putString("sealed", "bad").commit())
            assertThrows(IllegalStateException::class.java) { store.load() }
            assertEquals("bad", prefs.getString("sealed", null))
        } finally {
            prefs.edit().clear().commit()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry("${context.packageName}.$namespace")
            }
        }
    }
}
