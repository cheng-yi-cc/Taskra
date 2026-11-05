package com.taskra

import com.taskra.sync.SyncClient
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncClientTest {
    @Test fun refusesUnencryptedOrCredentialBearingServerAddresses() {
        assertThrows(IllegalArgumentException::class.java) { SyncClient("http://example.test") }
        assertThrows(IllegalArgumentException::class.java) { SyncClient("https://user:password@example.test") }
        assertThrows(IllegalArgumentException::class.java) { SyncClient("https://example.test/?token=secret") }
        SyncClient("https://example.test/taskra-sync")
    }
}
