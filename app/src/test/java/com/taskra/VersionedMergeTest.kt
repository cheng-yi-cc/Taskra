package com.taskra

import com.taskra.sync.SyncVersion
import com.taskra.sync.VersionedRecord
import com.taskra.sync.mergeRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VersionedMergeTest {
    private fun item(time: Long, device: String, value: String?) =
        VersionedRecord(SyncVersion(time, device, 1), value)

    @Test fun independentChangesAreKeptAndNewerEditWins() {
        val local = mapOf("a" to item(10, "phone", "older"), "b" to item(11, "phone", "phone only"))
        val remote = mapOf("a" to item(12, "tablet", "newer"), "c" to item(9, "tablet", "tablet only"))
        val merged = mergeRecords(local, remote)
        assertEquals(setOf("a", "b", "c"), merged.keys)
        assertEquals("newer", merged.getValue("a").value)
        assertEquals(merged, mergeRecords(remote, local))
    }

    @Test fun deletionPreventsStaleDeviceResurrectingRecord() {
        val deleted = mapOf("a" to item(20, "phone", null))
        val old = mapOf("a" to item(10, "tablet", "old"))
        assertEquals(null, mergeRecords(deleted, old).getValue("a").value)
    }

    @Test fun sameVersionWithDifferentContentsIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            mergeRecords(mapOf("a" to item(10, "phone", "one")), mapOf("a" to item(10, "phone", "two")))
        }
    }
}
