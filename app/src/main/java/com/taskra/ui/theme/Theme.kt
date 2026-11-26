package com.taskra.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.TaskraMotion
import com.taskra.ui.motion.rememberSystemReduceMotion

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

private fun lerpColorScheme(light: ColorScheme, dark: ColorScheme, fraction: Float): ColorScheme {
    if (fraction <= 0.001f) return light
    if (fraction >= 0.999f) return dark
    return light.copy(
        primary = lerp(light.primary, dark.primary, fraction),
        onPrimary = lerp(light.onPrimary, dark.onPrimary, fraction),
        primaryContainer = lerp(light.primaryContainer, dark.primaryContainer, fraction),
        onPrimaryContainer = lerp(light.onPrimaryContainer, dark.onPrimaryContainer, fraction),
        inversePrimary = lerp(light.inversePrimary, dark.inversePrimary, fraction),
        secondary = lerp(light.secondary, dark.secondary, fraction),
        onSecondary = lerp(light.onSecondary, dark.onSecondary, fraction),
        secondaryContainer = lerp(light.secondaryContainer, dark.secondaryContainer, fraction),
        onSecondaryContainer = lerp(light.onSecondaryContainer, dark.onSecondaryContainer, fraction),
        tertiary = lerp(light.tertiary, dark.tertiary, fraction),
        onTertiary = lerp(light.onTertiary, dark.onTertiary, fraction),
        tertiaryContainer = lerp(light.tertiaryContainer, dark.tertiaryContainer, fraction),
        onTertiaryContainer = lerp(light.onTertiaryContainer, dark.onTertiaryContainer, fraction),
        background = lerp(light.background, dark.background, fraction),
        onBackground = lerp(light.onBackground, dark.onBackground, fraction),
        surface = lerp(light.surface, dark.surface, fraction),
        onSurface = lerp(light.onSurface, dark.onSurface, fraction),
        surfaceVariant = lerp(light.surfaceVariant, dark.surfaceVariant, fraction),
        onSurfaceVariant = lerp(light.onSurfaceVariant, dark.onSurfaceVariant, fraction),
        inverseSurface = lerp(light.inverseSurface, dark.inverseSurface, fraction),
        inverseOnSurface = lerp(light.inverseOnSurface, dark.inverseOnSurface, fraction),
        error = lerp(light.error, dark.error, fraction),
        onError = lerp(light.onError, dark.onError, fraction),
        errorContainer = lerp(light.errorContainer, dark.errorContainer, fraction),
        onErrorContainer = lerp(light.onErrorContainer, dark.onErrorContainer, fraction),
        outline = lerp(light.outline, dark.outline, fraction),
        outlineVariant = lerp(light.outlineVariant, dark.outlineVariant, fraction),
        surfaceDim = lerp(light.surfaceDim, dark.surfaceDim, fraction),
        surfaceBright = lerp(light.surfaceBright, dark.surfaceBright, fraction),
        surfaceContainerLowest = lerp(light.surfaceContainerLowest, dark.surfaceContainerLowest, fraction),
        surfaceContainerLow = lerp(light.surfaceContainerLow, dark.surfaceContainerLow, fraction),
        surfaceContainer = lerp(light.surfaceContainer, dark.surfaceContainer, fraction),
        surfaceContainerHigh = lerp(light.surfaceContainerHigh, dark.surfaceContainerHigh, fraction),
        surfaceContainerHighest = lerp(light.surfaceContainerHighest, dark.surfaceContainerHighest, fraction),
    )
}

@Composable
fun TaskraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 首版不启用动态取色，防止壁纸把主题带成蓝紫
    content: @Composable () -> Unit
) {
    val reduceMotion = rememberSystemReduceMotion()
    // 单标量驱动深浅色平滑过渡：静止时零分配直接返回 LightScheme / DarkScheme，切色时柔和不刺眼。
    val darkFraction by animateFloatAsState(
        targetValue = if (darkTheme) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (reduceMotion) 0 else TaskraMotion.StandardMs,
            easing = TaskraMotion.Standard,
        ),
        label = "themeDarkFraction",
    )
    val scheme = lerpColorScheme(LightScheme, DarkScheme, darkFraction)
    val notebook = when {
        darkFraction <= 0.001f -> NotebookLight
        darkFraction >= 0.999f -> NotebookDark
        else -> NotebookColors(
            paper = lerp(NotebookLight.paper, NotebookDark.paper, darkFraction),
            grid = lerp(NotebookLight.grid, NotebookDark.grid, darkFraction),
            ink = lerp(NotebookLight.ink, NotebookDark.ink, darkFraction),
            red = lerp(NotebookLight.red, NotebookDark.red, darkFraction),
            paperSheet = lerp(NotebookLight.paperSheet, NotebookDark.paperSheet, darkFraction),
            dark = darkTheme,
        )
    }
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
    CompositionLocalProvider(
        LocalReduceMotion provides reduceMotion,
        LocalNotebook provides notebook,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TaskraTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(3.dp), small = RoundedCornerShape(4.dp),
                medium = RoundedCornerShape(6.dp), large = RoundedCornerShape(8.dp),
                extraLarge = RoundedCornerShape(12.dp),
            ),
            content = content
        )
    }
}
