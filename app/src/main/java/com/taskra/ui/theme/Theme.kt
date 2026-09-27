package com.taskra.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = BrandRed,
    onPrimary = Color.White,
    primaryContainer = BrandSoftBackground,
    onPrimaryContainer = Color(0xFF5C1F18),
    background = PageBackground,
    onBackground = InkColor,
    surface = CardBackground,
    onSurface = InkColor,
    surfaceVariant = Color(0xFFF3EDE4),
    onSurfaceVariant = InkSecondaryColor,
    secondary = Color(0xFF7A5C48),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E4D8),
    tertiary = SuccessGreen,
    error = Color(0xFFB3261E),
    outline = DividerColor,
    outlineVariant = DividerColor,
)

private val DarkScheme = darkColorScheme(
    primary = TaskraDark.Brand,
    onPrimary = Color(0xFF2A1512),
    primaryContainer = TaskraDark.BrandSoftBg,
    onPrimaryContainer = Color(0xFFF5D9D1),
    background = TaskraDark.PageBg,
    onBackground = TaskraDark.Ink,
    surface = TaskraDark.CardBg,
    onSurface = TaskraDark.Ink,
    surfaceVariant = Color(0xFF3A3532),
    onSurfaceVariant = TaskraDark.InkSecondary,
    secondary = Color(0xFFD6BFA9),
    tertiary = TaskraDark.Success,
    error = Color(0xFFFFB4AB),
    outline = TaskraDark.Divider,
    outlineVariant = TaskraDark.Divider,
)

@Composable
fun TaskraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 首版不启用动态取色，防止壁纸把主题带成蓝紫
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = TaskraTypography,
        content = content
    )
}
