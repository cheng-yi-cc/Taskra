package com.taskra.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.TaskraMotion
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToTrash(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    resetKey: Int = 0,
    swipeRight: Boolean = false,
    deleteLabel: String = "移到回收站",
    actionText: String = "回收站",
    cornerRadius: Dp = 5.dp,
    content: @Composable () -> Unit,
) {
    val reduced = LocalReduceMotion.current
    val haptic = LocalHapticFeedback.current
    val delete by rememberUpdatedState(onDelete)
    var deletionRequested by remember { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 112.dp.toPx() }
    val state = rememberSwipeToDismissBoxState(
        positionalThreshold = { minOf(it * 0.42f, threshold) },
        confirmValueChange = { value ->
            if (value == if (swipeRight) SwipeToDismissBoxValue.StartToEnd else SwipeToDismissBoxValue.EndToStart) {
                if (!deletionRequested) { deletionRequested = true; delete() }
                true
            }
            else value == SwipeToDismissBoxValue.Settled
        },
    )
    LaunchedEffect(resetKey) { deletionRequested = false; state.reset() }

    // 连续跟手进度：跨过删除阈值瞬间给一次清脆触觉和色彩/图标确认反馈
    val dragFraction by remember(state, threshold) {
        derivedStateOf {
            if (state.dismissDirection == SwipeToDismissBoxValue.Settled) 0f
            else runCatching { (abs(state.requireOffset()) / threshold).coerceIn(0f, 1.4f) }.getOrDefault(0f)
        }
    }
    val passedThreshold = dragFraction >= 1f
    var lastPassed by remember { mutableStateOf(false) }
    LaunchedEffect(passedThreshold) {
        if (passedThreshold != lastPassed) {
            if (state.dismissDirection != SwipeToDismissBoxValue.Settled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            lastPassed = passedThreshold
        }
    }

    SwipeToDismissBox(
        state = state,
        modifier = modifier.semantics {
            if (enabled) customActions = listOf(CustomAccessibilityAction(deleteLabel) {
                if (!deletionRequested) { deletionRequested = true; delete() }; true
            })
        },
        enableDismissFromStartToEnd = enabled && swipeRight,
        enableDismissFromEndToStart = enabled && !swipeRight,
        backgroundContent = {
            // 半透明纸卡静止时不能透出下面的删除操作，开始滑动后再随距离渐进显现。
            if (state.dismissDirection != SwipeToDismissBoxValue.Settled) {
                val revealAlpha = (dragFraction / 0.22f).coerceIn(0f, 1f)
                val bgColor by animateColorAsState(
                    targetValue = if (passedThreshold) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.errorContainer,
                    animationSpec = TaskraMotion.colorSpec(reduced, TaskraMotion.QuickMs),
                    label = "swipeBg",
                )
                val fgColor by animateColorAsState(
                    targetValue = if (passedThreshold) MaterialTheme.colorScheme.onError
                    else MaterialTheme.colorScheme.onErrorContainer,
                    animationSpec = TaskraMotion.colorSpec(reduced, TaskraMotion.QuickMs),
                    label = "swipeFg",
                )
                val iconScaleState = animateFloatAsState(
                    targetValue = when {
                        reduced -> 1f
                        passedThreshold -> 1.15f
                        else -> 0.80f + 0.20f * dragFraction.coerceIn(0f, 1f)
                    },
                    animationSpec = TaskraMotion.tactileSpring(),
                    label = "swipeIconScale",
                )
                Row(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = revealAlpha }
                        .clip(RoundedCornerShape(cornerRadius))
                        .background(bgColor)
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = if (swipeRight) Arrangement.Start else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = fgColor,
                        modifier = Modifier.graphicsLayer {
                            val s = iconScaleState.value
                            scaleX = s
                            scaleY = s
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(actionText, color = fgColor, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) { content() }
}
