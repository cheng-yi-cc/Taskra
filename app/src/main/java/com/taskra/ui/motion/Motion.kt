package com.taskra.ui.motion

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 系统“减少动态效果 / 移除动画”偏好。
 * 当系统 ANIMATOR_DURATION_SCALE == 0f 时为 true；所有空间位移、缩放、摇晃和粒子动画自动收敛为克制的淡入淡出。
 */
val LocalReduceMotion = compositionLocalOf { false }

private fun readSystemReduceMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
}.getOrDefault(false)

@Composable
fun rememberSystemReduceMotion(): Boolean {
    val view = LocalView.current
    if (view.isInEditMode) return false
    val context = LocalContext.current
    var reduced by remember(context) { mutableStateOf(readSystemReduceMotion(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = readSystemReduceMotion(context)
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
        }
        reduced = readSystemReduceMotion(context)
        onDispose {
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }
    return reduced
}

/**
 * Taskra 统一动效语言：一套苹果流体物理曲线与节奏令牌。
 * - 空间位移优先临界/轻阻尼弹簧：自然减速、随时可打断、不出现廉价橡胶弹跳。
 * - 高频微交互快而轻：150–220ms，不拖慢用户节奏。
 * - 全面尊重 [LocalReduceMotion]。
 */
object TaskraMotion {
    // 苹果流体快出缓停曲线（推入/弹出/抽屉/卡片展开）
    val FluidOut = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)

    // 柔和对称曲线（色彩、透明度、进度追随）
    val Standard = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f)

    // 快速显现曲线（淡入）
    val Decelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

    const val QuickMs = 150
    const val StandardMs = 220
    const val ModalMs = 280
    const val PageMs = 310
    const val ReducedFadeMs = 100

    /** 空间布局变化（展开/收起/高度自适应/归位）：无橡胶回弹，自然停稳。 */
    fun <T> spatialSpring(visibilityThreshold: T? = null): SpringSpec<T> = spring(
        dampingRatio = 0.94f,
        stiffness = 520f,
        visibilityThreshold = visibilityThreshold,
    )

    /** 触控微反馈（按压回弹、角标弹出、勾选确认、图标切换）：干脆轻盈。 */
    fun <T> tactileSpring(visibilityThreshold: T? = null): SpringSpec<T> = spring(
        dampingRatio = 0.74f,
        stiffness = 900f,
        visibilityThreshold = visibilityThreshold,
    )

    /** 列表项让位/重排动画：严格零回跳。 */
    fun listPlacementSpec(reduced: Boolean): FiniteAnimationSpec<IntOffset> =
        if (reduced) tween(0)
        else spring(
            dampingRatio = 0.98f,
            stiffness = 480f,
            visibilityThreshold = IntOffset.VisibilityThreshold,
        )

    /** 列表项淡入/淡出规格。 */
    fun listFadeSpec(reduced: Boolean): FiniteAnimationSpec<Float> =
        tween(if (reduced) ReducedFadeMs else QuickMs, easing = Decelerate)

    /** 拖拽松手落位弹簧：临界阻尼，单调收敛零越界。 */
    fun landingSpec(reduced: Boolean): FiniteAnimationSpec<Float> =
        if (reduced) tween(ReducedFadeMs, easing = Decelerate)
        else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 540f)

    /** 颜色/透明度统一短过渡。 */
    fun <T> colorSpec(reduced: Boolean, durationMs: Int = QuickMs): FiniteAnimationSpec<T> =
        tween(if (reduced) 0 else durationMs, easing = Standard)

    /** 高度/内容尺寸自适应弹簧。 */
    fun sizeSpec(reduced: Boolean): FiniteAnimationSpec<IntSize> =
        if (reduced) tween(0)
        else spatialSpring(visibilityThreshold = IntSize.VisibilityThreshold)

    /** 垂直平滑展开（设置子项、错误提示、筛选清除钮、系统提示行）。 */
    fun expandEnter(reduced: Boolean): EnterTransition =
        if (reduced) fadeIn(tween(ReducedFadeMs, easing = Decelerate))
        else fadeIn(tween(QuickMs, easing = Decelerate)) + expandVertically(
            animationSpec = spatialSpring(IntSize.VisibilityThreshold),
            expandFrom = Alignment.Top,
        )

    /** 垂直平滑收起。 */
    fun collapseExit(reduced: Boolean): ExitTransition =
        if (reduced) fadeOut(tween(ReducedFadeMs, easing = Standard))
        else fadeOut(tween(120, easing = Standard)) + shrinkVertically(
            animationSpec = spatialSpring(IntSize.VisibilityThreshold),
            shrinkTowards = Alignment.Top,
        )

    /** 轻弹缩放显现（角标、清除图标、悬浮微件）。 */
    fun popEnter(reduced: Boolean): EnterTransition =
        if (reduced) fadeIn(tween(ReducedFadeMs, easing = Decelerate))
        else fadeIn(tween(QuickMs, easing = Decelerate)) + scaleIn(
            animationSpec = tactileSpring(),
            initialScale = 0.84f,
        )

    /** 轻弹缩放退出。 */
    fun popExit(reduced: Boolean): ExitTransition =
        if (reduced) fadeOut(tween(ReducedFadeMs, easing = Standard))
        else fadeOut(tween(120, easing = Standard)) + scaleOut(
            animationSpec = tween(130, easing = Standard),
            targetScale = 0.84f,
        )

    /** 图标原位切换（播放/暂停、完成/恢复、保存/加载、草稿云）。 */
    fun iconSwapSpec(reduced: Boolean): ContentTransform =
        if (reduced) {
            fadeIn(tween(ReducedFadeMs, easing = Decelerate)) togetherWith
                fadeOut(tween(ReducedFadeMs, easing = Standard))
        } else {
            (fadeIn(tween(QuickMs, easing = Decelerate)) +
                scaleIn(animationSpec = tactileSpring(), initialScale = 0.82f)) togetherWith
                (fadeOut(tween(110, easing = Standard)) +
                    scaleOut(animationSpec = tween(110, easing = Standard), targetScale = 0.82f))
        }

    /** 方向性内容切换（日历翻月、日期↔时钟、工具栏主栏↔格式栏）。 */
    fun directionalSlideSpec(
        forward: Boolean,
        reduced: Boolean,
        offsetFraction: Float = 0.18f,
    ): ContentTransform = if (reduced) {
        fadeIn(tween(ReducedFadeMs, easing = Decelerate)) togetherWith
            fadeOut(tween(ReducedFadeMs, easing = Standard))
    } else {
        val dir = if (forward) 1 else -1
        (fadeIn(tween(180, delayMillis = 20, easing = Decelerate)) +
            slideInHorizontally(tween(StandardMs, easing = FluidOut)) { (it * offsetFraction * dir).toInt() }) togetherWith
            (fadeOut(tween(120, easing = Standard)) +
                slideOutHorizontally(tween(190, easing = FluidOut)) { (-it * offsetFraction * dir).toInt() })
    }

    /** 温和交叉淡入淡出（状态文案、面板内容切换）。 */
    fun crossfadeSpec(reduced: Boolean): ContentTransform =
        fadeIn(tween(if (reduced) ReducedFadeMs else QuickMs, easing = Decelerate)) togetherWith
            fadeOut(tween(if (reduced) ReducedFadeMs else 120, easing = Standard))
}

/**
 * 苹果式零重排触控反馈（基于 Compose 1.7 Modifier.Node）：
 * 按下微沉、松手弹簧回弹，同时支持平板鼠标/触控笔悬停微亮。
 * - 无 composed{} 子组合开销，无静止态动画 Channel；
 * - 仅在收到真实按压/悬停事件时启动弹簧，列表快速滑动零负担。
 */
fun Modifier.appleTactile(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.976f,
    cornerRadius: Dp = 5.dp,
    enabled: Boolean = true,
    hoverTint: Color = Color.Unspecified,
): Modifier = this then AppleTactileElement(
    interactionSource = interactionSource,
    pressedScale = pressedScale,
    cornerRadius = cornerRadius,
    enabled = enabled,
    drawOverlay = true,
    hoverTint = hoverTint,
)

/**
 * 轻量按压缩放（基于 Modifier.Node，适用于圆形按钮、FAB、图标按钮等已有容器态的控件）。
 */
fun Modifier.applePressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.93f,
    enabled: Boolean = true,
): Modifier = this then AppleTactileElement(
    interactionSource = interactionSource,
    pressedScale = pressedScale,
    cornerRadius = 0.dp,
    enabled = enabled,
    drawOverlay = false,
    hoverTint = Color.Unspecified,
)

private data class AppleTactileElement(
    val interactionSource: InteractionSource,
    val pressedScale: Float,
    val cornerRadius: Dp,
    val enabled: Boolean,
    val drawOverlay: Boolean,
    val hoverTint: Color,
) : ModifierNodeElement<AppleTactileNode>() {
    override fun create() = AppleTactileNode(
        interactionSource, pressedScale, cornerRadius, enabled, drawOverlay, hoverTint,
    )

    override fun update(node: AppleTactileNode) {
        node.updateParams(interactionSource, pressedScale, cornerRadius, enabled, drawOverlay, hoverTint)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "appleTactile"
        properties["pressedScale"] = pressedScale
        properties["enabled"] = enabled
    }
}

private class AppleTactileNode(
    private var interactionSource: InteractionSource,
    private var pressedScale: Float,
    private var cornerRadius: Dp,
    private var enabled: Boolean,
    private var drawOverlay: Boolean,
    private var hoverTint: Color,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode, CompositionLocalConsumerModifierNode {

    private var collectJob: Job? = null
    private var scaleJob: Job? = null
    private var overlayJob: Job? = null
    private var pressCount = 0
    private var hoverCount = 0
    private val scaleAnim = Animatable(1f)
    private val overlayAnim = Animatable(0f)
    private var layerActive by mutableStateOf(false)

    override fun onAttach() {
        startCollecting()
    }

    override fun onDetach() {
        collectJob?.cancel()
        scaleJob?.cancel()
        overlayJob?.cancel()
        collectJob = null
    }

    fun updateParams(
        interactionSource: InteractionSource,
        pressedScale: Float,
        cornerRadius: Dp,
        enabled: Boolean,
        drawOverlay: Boolean,
        hoverTint: Color,
    ) {
        val sourceChanged = this.interactionSource != interactionSource
        this.interactionSource = interactionSource
        this.pressedScale = pressedScale
        this.cornerRadius = cornerRadius
        this.enabled = enabled
        this.drawOverlay = drawOverlay
        this.hoverTint = hoverTint
        if (sourceChanged && isAttached) {
            pressCount = 0
            hoverCount = 0
            startCollecting()
        }
        if (isAttached) syncTargets()
    }

    private fun startCollecting() {
        collectJob?.cancel()
        collectJob = coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> { pressCount++; syncTargets() }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        pressCount = (pressCount - 1).coerceAtLeast(0); syncTargets()
                    }
                    is HoverInteraction.Enter -> { hoverCount++; syncTargets() }
                    is HoverInteraction.Exit -> {
                        hoverCount = (hoverCount - 1).coerceAtLeast(0); syncTargets()
                    }
                }
            }
        }
    }

    private fun syncTargets() {
        val reduced = currentValueOf(LocalReduceMotion)
        val pressed = enabled && pressCount > 0
        val hovered = enabled && hoverCount > 0
        val targetScale = if (pressed && !reduced) pressedScale else 1f
        if (scaleAnim.targetValue != targetScale || scaleAnim.value != targetScale) {
            scaleJob?.cancel()
            scaleJob = coroutineScope.launch {
                if (reduced) {
                    scaleAnim.snapTo(1f)
                    layerActive = false
                } else {
                    layerActive = true
                    try {
                        scaleAnim.animateTo(targetScale, TaskraMotion.tactileSpring())
                    } finally {
                        if (scaleAnim.value == 1f && !pressed) layerActive = false
                    }
                }
            }
        }
        if (drawOverlay) {
            val targetAlpha = when {
                pressed -> 0.055f
                hovered -> 0.035f
                else -> 0f
            }
            if (overlayAnim.targetValue != targetAlpha || overlayAnim.value != targetAlpha) {
                overlayJob?.cancel()
                overlayJob = coroutineScope.launch {
                    if (reduced) {
                        overlayAnim.snapTo(targetAlpha)
                    } else {
                        overlayAnim.animateTo(targetAlpha, tween(110, easing = TaskraMotion.Standard))
                    }
                }
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        val useLayer = layerActive
        return layout(placeable.width, placeable.height) {
            if (!useLayer) {
                placeable.place(0, 0)
            } else {
                placeable.placeWithLayer(0, 0) {
                    val s = scaleAnim.value
                    scaleX = s
                    scaleY = s
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (drawOverlay) {
            val a = overlayAnim.value
            if (a > 0.001f) {
                val base = if (hoverTint != Color.Unspecified) hoverTint else currentValueOf(LocalContentColor)
                val r = cornerRadius.toPx()
                drawRoundRect(
                    color = base.copy(alpha = a),
                    cornerRadius = CornerRadius(r, r),
                )
            }
        }
    }
}

/**
 * 苹果式校验失败水平轻震：trigger 递增时触发一次短促衰减微震 + 触觉反馈。
 * 减少动态效果模式下不位移，避免前庭不适。
 */
fun Modifier.errorShake(trigger: Int): Modifier = composed {
    val reduced = LocalReduceMotion.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val offsetX = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            if (!reduced) {
                val amp = with(density) { 6.dp.toPx() }
                offsetX.snapTo(0f)
                offsetX.animateTo(amp, tween(45, easing = TaskraMotion.Standard))
                offsetX.animateTo(-amp * 0.8f, tween(55, easing = TaskraMotion.Standard))
                offsetX.animateTo(amp * 0.45f, tween(50, easing = TaskraMotion.Standard))
                offsetX.animateTo(-amp * 0.2f, tween(45, easing = TaskraMotion.Standard))
                offsetX.animateTo(0f, tween(45, easing = TaskraMotion.FluidOut))
            }
        }
    }
    this.graphicsLayer { translationX = offsetX.value }
}

/**
 * 滚动计数文本：数字增大时新数字从下方上滑进入，减小时从上方下滑进入，等宽数字防横向抖动。
 */
@Composable
fun RollingCountText(
    count: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current,
    format: (Int) -> String = { it.toString() },
) {
    val reduced = LocalReduceMotion.current
    val tabularStyle = remember(style) { style.copy(fontFeatureSettings = "tnum") }
    AnimatedContent(
        targetState = count,
        modifier = modifier,
        transitionSpec = {
            if (reduced) {
                fadeIn(tween(TaskraMotion.ReducedFadeMs)) togetherWith
                    fadeOut(tween(TaskraMotion.ReducedFadeMs))
            } else {
                val increasing = targetState > initialState
                val slideIn = slideInVertically(tween(200, easing = TaskraMotion.FluidOut)) {
                    if (increasing) (it * 0.42f).toInt() else -(it * 0.42f).toInt()
                }
                val slideOut = slideOutVertically(tween(150, easing = TaskraMotion.Standard)) {
                    if (increasing) -(it * 0.42f).toInt() else (it * 0.42f).toInt()
                }
                (slideIn + fadeIn(tween(160, easing = TaskraMotion.Decelerate))) togetherWith
                    (slideOut + fadeOut(tween(110, easing = TaskraMotion.Standard)))
            }.using(
                sizeTransform = androidx.compose.animation.SizeTransform(clip = false)
            )
        },
        label = "rollingCount",
    ) { value ->
        Text(
            text = format(value),
            style = tabularStyle,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * 弹窗内容轻盈入场容器：克制的 0.95→1.0 弹簧缩放 + 淡入，高度变化自动平滑过渡。
 */
@Composable
fun AppleDialogContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduced = LocalReduceMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) {
            progress.animateTo(1f, TaskraMotion.spatialSpring())
        }
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                val p = progress.value
                alpha = p.coerceIn(0f, 1f)
                if (!reduced) {
                    val s = 0.94f + 0.06f * p
                    scaleX = s
                    scaleY = s
                }
            }
            .animateContentSize(animationSpec = TaskraMotion.sizeSpec(reduced)),
    ) {
        content()
    }
}

/**
 * 统一内容尺寸自适应修饰符，自动尊重系统“减少动态效果”。
 */
fun Modifier.appleContentSize(): Modifier = composed {
    animateContentSize(animationSpec = TaskraMotion.sizeSpec(LocalReduceMotion.current))
}
