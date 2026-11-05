package com.taskra

import com.taskra.sync.DEFAULT_SYNC_ENDPOINT
import com.taskra.sync.LEGACY_SYNC_ENDPOINT
import com.taskra.sync.SyncCredentials
import com.taskra.sync.upgradedSyncEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SyncEndpointTest {
    private val existing = SyncCredentials(LEGACY_SYNC_ENDPOINT, "device", "token")

    @Test fun verifiedEndpointPreservesCredentials() = runBlocking {
        val updated = upgradedSyncEndpoint(existing) { candidate ->
            assertEquals("device", candidate.deviceId)
            assertEquals("token", candidate.token)
            assertEquals(DEFAULT_SYNC_ENDPOINT, candidate.endpoint)
        }
        assertEquals(existing.copy(endpoint = DEFAULT_SYNC_ENDPOINT), updated)
    }

    @Test fun failureKeepsOldEndpointAndCustomEndpointsAreUntouched() = runBlocking {
        assertEquals(existing, upgradedSyncEndpoint(existing) { error("offline") })
        val custom = existing.copy(endpoint = "https://example.org/sync")
        assertEquals(custom, upgradedSyncEndpoint(custom) { error("must not probe") })
    }

    @Test fun cancellationIsNotSwallowed() = runBlocking {
        val failure = runCatching { upgradedSyncEndpoint(existing) { throw CancellationException() } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}
