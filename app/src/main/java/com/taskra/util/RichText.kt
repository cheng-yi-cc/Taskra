package com.taskra.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 富文本标记类型：加粗 / 高亮 / 标题级别（H1-H3 作用于整行）。 */
object SpanType {
    const val BOLD = "BOLD"
    const val HIGHLIGHT = "HIGHLIGHT"
    const val HEADING = "HEADING"
}

@Serializable
data class SpanMark(
    val type: String,
    val start: Int,
    val end: Int,
    val level: Int = 0, // HEADING: 1/2/3
)

val SpanCodec = Json { ignoreUnknownKeys = true }

fun encodeSpans(spans: List<SpanMark>): String = SpanCodec.encodeToString(spans)

fun decodeSpans(json: String?): List<SpanMark> = try {
    if (json.isNullOrBlank()) emptyList() else SpanCodec.decodeFromString(json)
} catch (_: Exception) {
    emptyList()
}

/** 规范化：裁剪到文本边界，去掉空区间，按起点排序。 */
fun normalizeSpans(spans: List<SpanMark>, len: Int): List<SpanMark> =
    spans.mapNotNull {
        val s = it.start.coerceIn(0, len)
        val e = it.end.coerceIn(0, len)
        if (s >= e) null else it.copy(start = s, end = e)
    }.sortedWith(compareBy({ it.start }, { it.end }, { it.type }))

/**
 * 文本变化后重映射标记（纯函数，可单元测试）。
 * 用公共前后缀定位变更区间：变更前严格在其前的标记不动，严格在其后的整体平移，
 * 与变更相交的：插入且落在标记内部则撑开，其他情况丢弃（避免残留错位格式）。
 */
fun adjustSpans(spans: List<SpanMark>, oldText: String, newText: String): List<SpanMark> {
    if (oldText == newText) return normalizeSpans(spans, newText.length)
    var p = 0
    val minLen = minOf(oldText.length, newText.length)
    while (p < minLen && oldText[p] == newText[p]) p++
    var sOld = oldText.length
    var sNew = newText.length
    while (sOld > p && sNew > p && oldText[sOld - 1] == newText[sNew - 1]) {
        sOld--
        sNew--
    }
    val delta = newText.length - oldText.length
    val insertedOnly = (sOld == p) // 纯插入（可能为空插入）
    return spans.mapNotNull { m ->
        when {
            m.end <= p -> m // 严格在变更前
            m.start >= sOld -> m.copy(start = m.start + delta, end = m.end + delta) // 严格在后
            insertedOnly && m.start <= p && m.end >= sOld -> {
                // 插入点落在标记内部（含端点）：撑开
                m.copy(end = m.end + delta)
            }
            else -> null // 删除相交或替换：丢弃，避免错位
        }
    }.let { normalizeSpans(it, newText.length) }
}

/** 在 [range] 上开关同类型标记：已完全覆盖则取消，否则替换为新区间的单个标记。返回新列表（未规范化，由调用方规范化）。 */
fun toggleMark(
    spans: List<SpanMark>,
    type: String,
    level: Int,
    range: IntRange,
): List<SpanMark> {
    if (range.first > range.last || range.isEmpty()) return spans
    val s = range.first
    val e = range.last + 1 // IntRange 尾包含，转为半开区间
    val covering = spans.filter {
        it.type == type && (if (type == SpanType.HEADING) it.level == level else true) &&
            it.start <= s && it.end >= e
    }
    val rest = spans.filterNot { it in covering || (it.type == type && rangesOverlap(it.start, it.end, s, e)) }
    return if (covering.isNotEmpty()) {
        rest
    } else {
        rest + SpanMark(type, s, e, level)
    }
}

private fun rangesOverlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean =
    maxOf(aStart, bStart) < minOf(aEnd, bEnd)

/** 裁剪标记到 [from, to) 并平移回 0 起点（拆分文字块时前后两段各取所需）。 */
fun sliceSpans(spans: List<SpanMark>, from: Int, to: Int): List<SpanMark> {
    if (from >= to) return emptyList()
    return spans.mapNotNull {
        val s = maxOf(it.start, from)
        val e = minOf(it.end, to)
        if (s >= e) null else it.copy(start = s - from, end = e - from)
    }
}

/** 取 offset 所在行区间 [lineStart, lineEnd)。 */
fun lineRangeOf(text: String, offset: Int): IntRange {
    val o = offset.coerceIn(0, text.length)
    val s = text.lastIndexOf('\n', o - 1).let { if (it < 0) 0 else it + 1 }
    val e = text.indexOf('\n', o).let { if (it < 0) text.length else it }
    return s until e
}

fun headingLevelAt(spans: List<SpanMark>, offset: Int): Int =
    spans.firstOrNull { it.type == SpanType.HEADING && it.start <= offset && it.end >= offset }?.level ?: 0

fun hasMarkAt(spans: List<SpanMark>, type: String, start: Int, end: Int): Boolean {
    if (start >= end) return false
    return spans.any { it.type == type && it.start <= start && it.end >= end }
}

/**
 * 由纯文本 + 标记构建 AnnotatedString。
 * @param baseFontSize 基准字号；HEADING 按 H1 1.35 / H2 1.2 / H3 1.1 放大。
 */
fun buildAnnotated(
    text: String,
    spans: List<SpanMark>,
    highlight: Color,
    baseFontSize: TextUnit = 16.sp,
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    val norm = normalizeSpans(spans, text.length)
    return buildAnnotatedString {
        append(text)
        norm.forEach { m ->
            val style = when (m.type) {
                SpanType.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                SpanType.HIGHLIGHT -> SpanStyle(background = highlight)
                SpanType.HEADING -> SpanStyle(
                    fontSize = baseFontSize * when (m.level) {
                        1 -> 1.35f
                        2 -> 1.2f
                        else -> 1.1f
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                else -> return@forEach
            }
            addStyle(style, m.start, m.end)
        }
    }
}

/** 供带样式的输入框使用：文本框内实时显示行内样式。 */
fun annotatedForEditing(
    text: String,
    spans: List<SpanMark>,
    highlight: Color,
    baseFontSize: TextUnit = 16.sp,
): AnnotatedString = buildAnnotated(text, spans, highlight, baseFontSize)

/** 高亮底色（跟随深浅主题，保证对比度）。 */
@Composable
fun rememberHighlightColor(): Color {
    val bg = androidx.compose.material3.MaterialTheme.colorScheme.background
    return if (bg.luminance() < 0.5f) Color(0xFF6B5D1E) else Color(0xFFFFF0A8)
}
