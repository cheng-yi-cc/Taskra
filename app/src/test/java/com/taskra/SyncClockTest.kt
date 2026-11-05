package com.taskra

import com.taskra.sync.ClockSample
import com.taskra.sync.assessChangeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncClockTest {
    private val sample = ClockSample(1_000_000, 1_000_100, 5_000, 5_100, 1_000_250, 9)

    @Test fun recentStableClockUsesServerAdjustedEditTime() {
        val result = assessChangeTime(1_010_000, sample, 1_020_000, 25_000, 9)
        assertTrue(result.reliable)
        assertEquals(1_010_200, result.modifiedAt)
    }

    @Test fun uncertainClockFallsBackToServerArrivalOrder() {
        assertFalse(assessChangeTime(999_999, sample, 1_020_000, 25_000, 9).reliable)
        assertFalse(assessChangeTime(1_010_000, sample, 1_020_000, 25_000, 10).reliable)
        assertFalse(assessChangeTime(1_010_000, sample, 1_040_000, 25_000, 9).reliable)
        assertFalse(assessChangeTime(1_010_000, null, 1_020_000, 25_000, 9).reliable)
    }
}
