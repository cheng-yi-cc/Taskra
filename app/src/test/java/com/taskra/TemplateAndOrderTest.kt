package com.taskra

import com.taskra.data.*
import com.taskra.util.*
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class TemplateAndOrderTest {
    @Test fun weeklyCutoffUsesUpcomingOccurrenceAndDateOnlyEndsAtMidnight() {
        val now = ZonedDateTime.parse("2026-09-29T18:00:00+08:00[Asia/Shanghai]")
        assertEquals("2026-09-29", CourseTemplate(weekday = 2).nextDate(now))
        assertEquals("2026-09-29", CourseTemplate(weekday = 2, time = "18:01").nextDate(now))
        assertEquals("2026-10-06", CourseTemplate(weekday = 2, time = "18:00").nextDate(now))
        assertEquals("2026-10-05", CourseTemplate(weekday = 1).nextDate(now))
        assertNull(CourseTemplate().nextDate(now))
    }
    @Test fun filteredReorderPreservesHiddenSlotsAndAddsNewItems() {
        assertEquals(listOf("c", "hidden", "a", "b", "new"), mergeVisibleOrder(listOf("a", "hidden", "b", "c"), listOf("c", "a", "b", "new")))
        assertEquals(listOf("b", "a", "new"), ordered(listOf("a", "b", "new"), listOf("b", "a")) { it })
    }
    @Test fun templateRoundTripPreservesAllMediaAndMarks() {
        val t = CourseTemplate(listOf(BlockJson("text", "TEXT", "要求", spans = listOf(SpanMark(SpanType.BOLD, 0, 2))), BlockJson("image", "IMAGE", imageId = "asset"), BlockJson("audio", "AUDIO", audioId = "voice", audioDurationMs = 800)), 7, "23:00")
        assertEquals(t, decodeTemplate(encodeTemplate(t)))
        assertNull(decodeTemplate(null))
    }
}
