package com.taskra.ui.components

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToTrash(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    resetKey: Int = 0,
    swipeRight: Boolean = false,
    deleteLabel: String = "移到回收站",
    content: @Composable () -> Unit,
) {
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
            // 半透明纸卡静止时不能透出下面的删除操作，开始滑动后再显示。
            if (state.dismissDirection != SwipeToDismissBoxValue.Settled) {
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 22.dp),
                horizontalArrangement = if (swipeRight) Arrangement.Start else Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text("删除", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
            }
            }
        },
    ) { content() }
}
