package com.taskra.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.TaskraMotion

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
    var onSwap: () -> Unit = {}
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
        // 允许在落位动画进行中直接重新抓起，自然打断并衔接
        if (settling != null) {
            settling = null
            landingProgress = 1f
        }
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
        onSwap()
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
    suspend fun finishLanding(id: String, reduced: Boolean) {
        // 最后一次换位和松手可能同帧到达，等新的槽位布局就绪再落位。
        withFrameNanos { }
        withFrameNanos { }
        if (settling != id) return
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = TaskraMotion.landingSpec(reduced),
        ) { value, _ ->
            if (settling == id) landingProgress = value
        }
        if (settling == id) settling = null
    }
    fun translation(id: String): Float {
        // 非拖拽且非落位卡片直接返回 0f，避免在正常滚动期间订阅 list.layoutInfo 触发每帧无效化。
        if (id != dragging && id != settling) return 0f
        val offset = list.layoutInfo.visibleItemsInfo.find { it.key == id }?.offset ?: return 0f
        return if (id == dragging) top - offset else (landingTop - offset) * (1f - landingProgress)
    }
}

@Composable
fun rememberDragOrder(list: LazyListState, ids: List<String>, onCommit: (List<String>, () -> Unit) -> Unit): DragOrderState {
    val reduced = LocalReduceMotion.current
    val haptic = LocalHapticFeedback.current
    val state = remember(list) { DragOrderState(list) }
    state.onCommit = onCommit
    state.onSwap = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    LaunchedEffect(ids) { state.acceptSource(ids) }
    LaunchedEffect(state.settling, reduced) { state.settling?.let { state.finishLanding(it, reduced) } }
    // 帧同步渐进边缘自动滚动：贴近边缘距离越近速度越丝滑，兼容 60/90/120Hz 高刷屏。
    LaunchedEffect(state.dragging) {
        var lastNanos = 0L
        while (state.dragging != null) {
            withFrameNanos { frameNanos ->
                val dtSec = if (lastNanos == 0L) 0.016f
                else ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0.004f, 0.05f)
                lastNanos = frameNanos
                val info = list.layoutInfo
                val edge = 120f
                val topOverflow = (info.viewportStartOffset + edge) - state.top
                val bottomOverflow = (state.top + state.height) - (info.viewportEndOffset - edge)
                val speedPxPerSec = when {
                    topOverflow > 0f -> -((topOverflow / edge).coerceIn(0.15f, 1.25f) * 980f)
                    bottomOverflow > 0f -> ((bottomOverflow / edge).coerceIn(0.15f, 1.25f) * 980f)
                    else -> 0f
                }
                if (speedPxPerSec != 0f) {
                    val delta = speedPxPerSec * dtSec
                    state.direction = delta
                    list.dispatchRawDelta(delta)
                    state.swap()
                }
            }
        }
    }
    return state
}

/** 浮起的卡片只有手指/落位位移；列表 placement 动画仅负责其他卡片让位。 */
@Composable
fun LazyItemScope.reorderItemModifier(state: DragOrderState, id: String, enabled: Boolean = true): Modifier {
    val reduced = LocalReduceMotion.current
    return Modifier.animateItem(
        fadeInSpec = TaskraMotion.listFadeSpec(reduced),
        fadeOutSpec = TaskraMotion.listFadeSpec(reduced),
        placementSpec = if (state.isFloating(id)) null else TaskraMotion.listPlacementSpec(reduced),
    ).dragOrder(state, id, enabled)
}

@Composable
private fun Modifier.dragOrder(state: DragOrderState, id: String, enabled: Boolean): Modifier {
    val reduced = LocalReduceMotion.current
    val haptic = LocalHapticFeedback.current
    val isDragging = state.dragging == id
    // 静止态零动画 Channel：仅当本卡片被真正抓起/放下时启动弹簧。
    val lift = remember { Animatable(0f) }
    LaunchedEffect(isDragging, reduced) {
        val target = if (isDragging) 1f else 0f
        if (lift.value == target) return@LaunchedEffect
        if (reduced) lift.snapTo(target)
        else lift.animateTo(target, TaskraMotion.tactileSpring())
    }
    return this.zIndex(if (state.isFloating(id)) 1f else 0f)
        // 手势在未缩放的坐标系采样，抬起缩放不能改变手指位移。
        .pointerInput(state, id, enabled) {
            if (enabled) detectDragGesturesAfterLongPress(
                onDragStart = { if (state.start(id)) haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                onDragEnd = { state.end() }, onDragCancel = { state.end() },
                onDrag = { change, delta -> if (state.dragging == id) { change.consume(); state.move(delta.y) } },
            )
        }.graphicsLayer {
            val l = lift.value
            val scale = if (reduced) 1f else 1f + 0.026f * l
            translationY = state.translation(id)
            scaleX = scale
            scaleY = scale
            shadowElevation = if (reduced) 0f else l * 14.dp.toPx()
        }
}
