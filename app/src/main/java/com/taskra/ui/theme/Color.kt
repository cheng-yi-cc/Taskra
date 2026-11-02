package com.taskra.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// 设计令牌（需求规格初值，可为可读性微调；禁止蓝紫主视觉）
object TaskraTokens {
    const val PageBg = 0xFFFAF7F2
    const val CardBg = 0xFFFFFFFF
    const val Brand = 0xFFB94335
    const val BrandSoftBg = 0xFFFCE8E2
    const val Ink = 0xFF292724
    const val InkSecondary = 0xFF716A62
    const val Divider = 0xFFE9E3DA
    const val SuccessSoft = 0xFF2F6B4F
    const val AmberSoft = 0xFF9A6B2E
    const val AmberSoftBg = 0xFFFAF0DC
    const val PagePaddingDp = 16
    const val CardRadiusDp = 14
}

val PageBackground = Color(TaskraTokens.PageBg)
val CardBackground = Color(TaskraTokens.CardBg)
val BrandRed = Color(TaskraTokens.Brand)
val BrandSoftBackground = Color(TaskraTokens.BrandSoftBg)
val InkColor = Color(TaskraTokens.Ink)
val InkSecondaryColor = Color(TaskraTokens.InkSecondary)
val DividerColor = Color(TaskraTokens.Divider)
val SuccessGreen = Color(TaskraTokens.SuccessSoft)
val AmberWarn = Color(TaskraTokens.AmberSoft)
val AmberWarnBg = Color(TaskraTokens.AmberSoftBg)

// 低饱和课程标签色（暖色系为主，避免蓝紫）
val CoursePalette = listOf(
    Color(0xFFC96F4A), // 陶土
    Color(0xFF8A9A5B), // 苔绿
    Color(0xFFC9A227), // 芥黄
    Color(0xFF7FA6A3), // 灰青（低饱和，不做主色）
    Color(0xFFB5838D), // 豆沙
    Color(0xFF6D8EA0), // 雾灰蓝（仅标签点缀）
    Color(0xFFD08C60),
    Color(0xFF997B66),
)

fun courseColor(index: Int): Color = CoursePalette[index.mod(CoursePalette.size)]

/**
 * 方格纸配色：纸面、格线、墨色、批注红。
 *
 * 格线只比纸面深一档，正文压在上面仍然清楚；深色模式换成暖灰纸，
 * 格线同样是低对比度，避免满屏亮线。
 */
@Immutable
data class NotebookColors(
    val paper: Color,
    val grid: Color,
    val ink: Color,
    val red: Color,
    val paperSheet: Color,
    val dark: Boolean,
)

val NotebookLight = NotebookColors(
    paper = PageBackground,
    grid = Color(0xFFE2DBD0),
    ink = InkColor,
    red = BrandRed,
    paperSheet = CardBackground,
    dark = false,
)

val NotebookDark = NotebookColors(
    paper = TaskraDark.PageBg,
    grid = Color(0xFF3B3530),
    ink = TaskraDark.Ink,
    red = TaskraDark.Brand,
    paperSheet = TaskraDark.CardBg,
    dark = true,
)

val LocalNotebook = staticCompositionLocalOf { NotebookLight }

// 深色模式色板：暖灰底 + 同一品牌红（降亮度保对比度）
object TaskraDark {
    val PageBg = Color(0xFF1C1917)
    val CardBg = Color(0xFF292524)
    val Brand = Color(0xFFE07864)
    val BrandSoftBg = Color(0xFF3E2A26)
    val Ink = Color(0xFFF5F0E8)
    val InkSecondary = Color(0xFFA8A29E)
    val Divider = Color(0xFF44403C)
    val Success = Color(0xFF86C5A5)
    val Amber = Color(0xFFE3B86B)
    val AmberBg = Color(0xFF3A3226)
}
