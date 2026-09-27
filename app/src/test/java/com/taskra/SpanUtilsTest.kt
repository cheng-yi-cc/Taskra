package com.taskra

import com.taskra.util.SpanMark
import com.taskra.util.SpanType
import com.taskra.util.adjustSpans
import com.taskra.util.decodeSpans
import com.taskra.util.encodeSpans
import com.taskra.util.headingLevelAt
import com.taskra.util.lineRangeOf
import com.taskra.util.normalizeSpans
import com.taskra.util.toggleMark
import org.junit.Assert.*
import org.junit.Test

class SpanUtilsTest {

    @Test
    fun `插入后标记平移与撑开`() {
        // "ab|cd" 在 2 处插入 "XY" → "abXYcd"
        val spans = listOf(SpanMark(SpanType.BOLD, 0, 2), SpanMark(SpanType.BOLD, 2, 4))
        val out = adjustSpans(spans, "abcd", "abXYcd")
        assertEquals(listOf(SpanMark(SpanType.BOLD, 0, 2), SpanMark(SpanType.BOLD, 4, 6)), out)
        // 插入点在标记内部则撑开："abc" 粗体 0-3，中间插入 → 0-4
        val out2 = adjustSpans(listOf(SpanMark(SpanType.BOLD, 0, 3)), "abc", "aXbc")
        assertEquals(listOf(SpanMark(SpanType.BOLD, 0, 4)), out2)
    }

    @Test
    fun `删除相交标记被丢弃`() {
        // "abcde" 粗体 1-4，删掉 2-3 → "abde"，标记与删除相交应丢弃
        val out = adjustSpans(listOf(SpanMark(SpanType.BOLD, 1, 4)), "abcde", "abde")
        assertTrue(out.isEmpty())
        // 严格在后的标记平移
        val out2 = adjustSpans(listOf(SpanMark(SpanType.BOLD, 3, 5)), "abcde", "abde")
        assertEquals(listOf(SpanMark(SpanType.BOLD, 2, 4)), out2)
    }

    @Test
    fun `开关标记`() {
        // 空选区外：添加
        val added = toggleMark(emptyList(), SpanType.BOLD, 0, 1..3)
        assertEquals(listOf(SpanMark(SpanType.BOLD, 1, 4)), added)
        // 已完全覆盖：取消
        val removed = toggleMark(added, SpanType.BOLD, 0, 1..3)
        assertTrue(removed.isEmpty())
        // 部分重叠：替换为新区间
        val replaced = toggleMark(listOf(SpanMark(SpanType.BOLD, 0, 2)), SpanType.BOLD, 0, 1..3)
        assertEquals(listOf(SpanMark(SpanType.BOLD, 1, 4)), replaced)
    }

    @Test
    fun `行区间与标题级别`() {
        val text = "第一行\n第二行\n第三行"
        assertEquals(0..2, lineRangeOf(text, 1).let { it.first..it.last })
        val line2 = lineRangeOf(text, 5)
        assertEquals("第二行", text.substring(line2.first, line2.last + 1))
        val spans = listOf(SpanMark(SpanType.HEADING, line2.first, line2.last + 1, 1))
        assertEquals(1, headingLevelAt(spans, 5))
        assertEquals(0, headingLevelAt(spans, 1))
    }

    @Test
    fun `编解码往返与老数据兼容`() {
        val spans = listOf(SpanMark(SpanType.BOLD, 0, 2), SpanMark(SpanType.HEADING, 3, 6, 2))
        val json = encodeSpans(spans)
        assertEquals(spans, decodeSpans(json))
        assertEquals(emptyList<SpanMark>(), decodeSpans(null))
        assertEquals(emptyList<SpanMark>(), decodeSpans(""))
        assertEquals(emptyList<SpanMark>(), decodeSpans("not-json"))
        // 空数组
        assertEquals(emptyList<SpanMark>(), decodeSpans("[]"))
    }

    @Test
    fun `规范化裁剪越界`() {
        val out = normalizeSpans(
            listOf(SpanMark(SpanType.BOLD, -2, 3), SpanMark(SpanType.BOLD, 2, 99), SpanMark(SpanType.BOLD, 3, 3)),
            5,
        )
        assertEquals(listOf(SpanMark(SpanType.BOLD, 0, 3), SpanMark(SpanType.BOLD, 2, 5)), out)
    }
}
