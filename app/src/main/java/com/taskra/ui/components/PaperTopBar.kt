package com.taskra.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.TaskraMotion
import com.taskra.ui.motion.applePressScale

/** 纸面顶栏：内容静止置顶时融于纸面，向下滚动后柔和浮现 0.8dp 纸边细线。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrolled: Boolean = false,
) {
    val reduced = LocalReduceMotion.current
    val dividerAlpha by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = tween(if (reduced) 0 else TaskraMotion.QuickMs, easing = TaskraMotion.Standard),
        label = "topBarHairline",
    )
    val edge = MaterialTheme.colorScheme.outlineVariant
    TopAppBar(
        title = { ProvideTextStyle(MaterialTheme.typography.titleMedium) { title() } },
        navigationIcon = navigationIcon,
        actions = actions,
        modifier = Modifier.drawBehind {
            if (dividerAlpha > 0.005f) {
                drawLine(
                    color = edge.copy(alpha = edge.alpha * dividerAlpha),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 0.8.dp.toPx(),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** 统一返回按钮：带轻量按压手感与一致的无障碍语义。 */
@Composable
fun PaperBackButton(onClick: () -> Unit, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier.applePressScale(interaction, 0.88f, enabled),
    ) {
        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
    }
}
