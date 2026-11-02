package com.taskra.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

@Stable
class DragOrderState(val list: LazyListState) {
    var ids by mutableStateOf(emptyList<String>())
        private set
    var dragging by mutableStateOf<String?>(null)
        private set
    var settling by mutableStateOf<String?>(null)
        private set
    var top by mutableFloatStateOf(0f)
        private set
    var height by mutableIntStateOf(0)
        private set
    var direction by mutableFloatStateOf(0f)
    private var landingProgress by mutableFloatStateOf(0f)
    var onCommit: (List<String>, () -> Unit) -> Unit = { _, _ -> }
    private var startOrder = emptyList<String>()
    private var sourceIds = emptyList<String>()
    private var pendingOrder: List<String>? = null
    private var landingTop = 0f

    fun acceptSource(source: List<String>) {
        sourceIds = source
        if (pendingOrder == source) pendingOrder = null
        if (dragging == null && pendingOrder == null) ids = source
        else {
            // 删除/新增可同步，但异步保存的旧顺序不能盖回手指刚排好的顺序。
            ids = ids.filter { it in source } + source.filter { it !in ids }
            if (dragging != null && dragging !in source) dragging = null
        }
        if (settling != null && settling !in source) settling = null
    }
    fun isFloating(id: String) = dragging == id || settling == id
    fun start(id: String): Boolean {
        if (settling != null) return false
        val item = list.layoutInfo.visibleItemsInfo.find { it.key == id } ?: return false
        startOrder = ids
        dragging = id; top = item.offset.toFloat(); height = item.size; direction = 0f
        return true
    }
    fun move(delta: Float) { if (dragging != null) { top += delta; direction = delta; swap() } }
    fun swap() {
        val id = dragging ?: return
        val from = ids.indexOf(id)
        val center = top + height / 2
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != id && it.key in ids && center in it.offset.toFloat()..(it.offset + it.size).toFloat()
        } ?: return
        val to = ids.indexOf(target.key)
        if (from < 0 || to < 0 || (to > from && direction <= 0) || (to < from && direction >= 0)) return
        // 固定视口索引，避免 LazyColumn 为保持首项 key 而自动跳动滚动位置。
        list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        ids = ids.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun end() {
        val id = dragging ?: return
        landingProgress = 0f
        landingTop = top
        settling = id
        dragging = null
        if (ids != startOrder) {
            val submitted = ids
            pendingOrder = submitted
            onCommit(submitted) {
                if (pendingOrder == submitted) { pendingOrder = null; ids = sourceIds }
            }
        }
    }
    suspend fun finishLanding(id: String) {
        // 最后一次换位和松手可能同帧到达，等新的槽位布局就绪再落位。
        withFrameNanos { }
        withFrameNanos { }
        animate(0f, 1f, animationSpec = tween(220, easing = FastOutSlowInEasing)) { value, _ -> landingProgress = value }
        if (settling == id) settling = null
    }
    fun translation(id: String): Float {
        val offset = list.layoutInfo.visibleItemsInfo.find { it.key == id }?.offset ?: return 0f
        return when (id) {
            dragging -> top - offset
            settling -> (landingTop - offset) * (1f - landingProgress)
            else -> 0f
        }
    }
}

@Composable
fun rememberDragOrder(list: LazyListState, ids: List<String>, onCommit: (List<String>, () -> Unit) -> Unit): DragOrderState {
    val state = remember(list) { DragOrderState(list) }
    state.onCommit = onCommit
    LaunchedEffect(ids) { state.acceptSource(ids) }
    LaunchedEffect(state.settling) { state.settling?.let { state.finishLanding(it) } }
    LaunchedEffect(state.dragging) {
        while (state.dragging != null) {
            val info = list.layoutInfo
            val edge = 90f
            val delta = when {
                state.top < info.viewportStartOffset + edge -> -14f
                state.top + state.height > info.viewportEndOffset - edge -> 14f
                else -> 0f
            }
            if (delta != 0f) { state.direction = delta; list.scrollBy(delta); state.swap() }
            delay(16)
        }
    }
    return state
}

/** 浮起的卡片只有手指/落位位移；列表 placement 动画仅负责其他卡片让位。 */
@Composable
fun LazyItemScope.reorderItemModifier(state: DragOrderState, id: String, enabled: Boolean = true): Modifier =
    Modifier.animateItem(
        fadeInSpec = null, fadeOutSpec = null,
        placementSpec = if (state.isFloating(id)) null else spring(dampingRatio = 1f, stiffness = 400f),
    ).dragOrder(state, id, enabled)

@Composable
private fun Modifier.dragOrder(state: DragOrderState, id: String, enabled: Boolean): Modifier {
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(if (state.dragging == id) 1.025f else 1f, tween(160), label = "cardLift")
    return this.zIndex(if (state.isFloating(id)) 1f else 0f)
        // 手势在未缩放的坐标系采样，抬起缩放不能改变手指位移。
        .pointerInput(state, id, enabled) {
            if (enabled) detectDragGesturesAfterLongPress(
                onDragStart = { if (state.start(id)) haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                onDragEnd = { state.end() }, onDragCancel = { state.end() },
                onDrag = { change, delta -> if (state.dragging == id) { change.consume(); state.move(delta.y) } },
            )
        }.graphicsLayer {
            translationY = state.translation(id)
            scaleX = scale; scaleY = scale
            shadowElevation = (scale - 1f) * 480f
        }
}
