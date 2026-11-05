package com.taskra.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

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
    onSecondaryContainer = InkColor,
    tertiary = SuccessGreen,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE5EBE1),
    onTertiaryContainer = Color(0xFF263B2B),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFFEDE8E1),
    surfaceBright = PageBackground,
    surfaceContainerLowest = CardBackground,
    surfaceContainerLow = PageBackground,
    surfaceContainer = Color(0xFFF2EEE7),
    surfaceContainerHigh = Color(0xFFEDE8E1),
    surfaceContainerHighest = Color(0xFFE7E1D9),
    error = Color(0xFFB3261E),
    outline = Color(0xFFB8AFA4),
    outlineVariant = DividerColor,
    inversePrimary = TaskraDark.Brand,
    inverseSurface = InkColor,
    inverseOnSurface = PageBackground,
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
    onSecondary = Color(0xFF332920),
    secondaryContainer = Color(0xFF39312C),
    onSecondaryContainer = TaskraDark.Ink,
    tertiary = TaskraDark.Success,
    onTertiary = Color(0xFF182D20),
    tertiaryContainer = Color(0xFF2B3B30),
    onTertiaryContainer = Color(0xFFD8E9D8),
    surfaceTint = Color.Transparent,
    surfaceDim = TaskraDark.PageBg,
    surfaceBright = Color(0xFF37322E),
    surfaceContainerLowest = Color(0xFF151311),
    surfaceContainerLow = TaskraDark.PageBg,
    surfaceContainer = TaskraDark.CardBg,
    surfaceContainerHigh = Color(0xFF302B27),
    surfaceContainerHighest = Color(0xFF3B3530),
    error = Color(0xFFFFB4AB),
    outline = Color(0xFF81766C),
    outlineVariant = TaskraDark.Divider,
    inversePrimary = BrandRed,
    inverseSurface = TaskraDark.Ink,
    inverseOnSurface = TaskraDark.PageBg,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun TaskraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 首版不启用动态取色，防止壁纸把主题带成蓝紫
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity()
            activity?.window?.let { window ->
                val bg = (if (darkTheme) TaskraDark.PageBg else PageBackground).toArgb()
                window.decorView.setBackgroundColor(bg)
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = TaskraTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(3.dp), small = RoundedCornerShape(4.dp),
            medium = RoundedCornerShape(6.dp), large = RoundedCornerShape(8.dp),
            extraLarge = RoundedCornerShape(12.dp),
        ),
        content = content
    )
}
