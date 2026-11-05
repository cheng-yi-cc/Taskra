package com.taskra

import com.taskra.sync.cloudDraftId
import com.taskra.sync.localDraftId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DraftIdentityTest {
    @Test fun newDraftsFromDifferentDevicesStaySeparateAndCanBeContinuedRemotely() {
        val phoneCloud = cloudDraftId("new", "phone")
        val tabletCloud = cloudDraftId("new", "tablet")
        assertNotEquals(phoneCloud, tabletCloud)
        assertEquals("new:phone", localDraftId(phoneCloud, "tablet"))
        assertEquals(phoneCloud, cloudDraftId("new:phone", "tablet"))
        assertEquals("new", localDraftId(phoneCloud, "phone"))
        assertEquals("edit:h1", cloudDraftId("edit:h1", "phone"))
    }
}
