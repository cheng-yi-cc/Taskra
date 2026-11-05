package com.taskra.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sin

/**
 * 开屏手写动画（仅在进程首次进入时播放，点击任意处可跳过）：
 * 钢笔随手腕倾角灵动摆动，一气呵成写出带有起笔顿锋、行笔抑扬粗细变化与尾端出锋的流畅游丝曲线，
 * 「课迹 / TASKRA」随运笔节奏渐次浮现。
 */
@Composable
fun OpeningAnimation(onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(1650, easing = LinearEasing))
        finished()
    }
    val p = progress.value
    val ink = MaterialTheme.colorScheme.onBackground
    val inkSoft = MaterialTheme.colorScheme.onSurfaceVariant
    val red = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.background

    // 阶段划分：0.05..0.76 一气呵成书写流畅游丝曲线，0.76..0.85 收笔轻提，0.86..1.00 整体淡出
    val rawFlourish = ((p - 0.05f) / 0.71f).coerceIn(0f, 1f)
    val flourish = FastOutSlowInEasing.transform(rawFlourish)
    val titleReveal = FastOutSlowInEasing.transform(((p - 0.12f) / 0.50f).coerceIn(0f, 1f))
    val subtitleReveal = FastOutSlowInEasing.transform(((p - 0.36f) / 0.42f).coerceIn(0f, 1f))
    val opacity = 1f - ((p - 0.86f) / 0.14f).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .testTag("opening_animation")
            .graphicsLayer { alpha = opacity }
            .notebookPaper()
            .pointerInput(Unit) { detectTapGestures { finished() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(width = 268.dp, height = 164.dp)) {
                withTransform({ scale(size.width / 268f, size.height / 164f, pivot = Offset.Zero) }) {
                    // 1. 绘制一气呵成的书法游丝曲线（带起笔顿锋、中段抑扬与尾端出锋的压感粗细变化）
                    val steps = 72
                    val activeSteps = (steps * flourish).toInt()
                    var prev = flourishPoint(0f)
                    for (i in 1..activeSteps) {
                        val t0 = (i - 1).toFloat() / steps
                        val t1 = i.toFloat() / steps
                        val cur = flourishPoint(t1)
                        val w = flourishStrokeWidth((t0 + t1) * 0.5f)
                        drawLine(
                            color = ink,
                            start = prev,
                            end = cur,
                            strokeWidth = w,
                            cap = StrokeCap.Round,
                        )
                        prev = cur
                    }
                    // 当前游丝末端插值点
                    val headPoint = flourishPoint(flourish)
                    if (flourish > 0f && flourish < 1f) {
                        drawLine(
                            color = ink,
                            start = prev,
                            end = headPoint,
                            strokeWidth = flourishStrokeWidth(flourish),
                            cap = StrokeCap.Round,
                        )
                    }
                    // 起笔处一枚微小的墨滴顿点，增强钢笔落纸质感
                    if (flourish > 0.01f) {
                        val p0 = flourishPoint(0f)
                        drawCircle(ink, radius = 1.85f, center = p0)
                    }

                    // 2. 计算钢笔笔尖实时坐标与手腕动态倾角
                    val dt = 0.02f
                    val pA = flourishPoint((flourish - dt).coerceAtLeast(0f))
                    val pB = flourishPoint((flourish + dt).coerceAtMost(1f))
                    val slopeDeg = atan2(pB.y - pA.y, (pB.x - pA.x).coerceAtLeast(0.1f)) * (180f / PI.toFloat())
                    val settle = ((p - 0.76f) / 0.10f).coerceIn(0f, 1f)
                    val penPos = Offset(
                        x = headPoint.x + settle * 4.5f,
                        y = headPoint.y - settle * 6.5f,
                    )
                    // 基准握笔角 31°，随曲线斜率自然摆动，收笔时轻提手腕
                    val penAngle = 31f + (slopeDeg * 0.28f).coerceIn(-9f, 9f) - settle * 5f

                    drawFountainPen(
                        tip = penPos,
                        angleDeg = penAngle,
                        ink = ink,
                        red = red,
                        paper = paper,
                    )
                }
            }
            Text(
                text = "课迹",
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 38.sp),
                color = ink,
                modifier = Modifier.graphicsLayer {
                    alpha = titleReveal
                    translationY = (1f - titleReveal) * 14f
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "TASKRA  ·  纸墨待办",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.4.sp),
                color = inkSoft,
                modifier = Modifier.graphicsLayer {
                    alpha = subtitleReveal
                    translationY = (1f - subtitleReveal) * 8f
                },
            )
            Spacer(Modifier.height(52.dp))
        }
    }
}

/**
 * 书法游丝轨迹：两段C1连续的三次贝塞尔拼合而成的舒展S形游丝曲线。
 * 左起 (34, 126) 顿笔上扬，中段舒展起伏，右至 (234, 117) 轻挑出锋，整体居中平衡。
 */
private fun flourishPoint(t: Float): Offset {
    val u = t.coerceIn(0f, 1f)
    return if (u <= 0.52f) {
        val s = u / 0.52f
        cubicBezier(
            p0 = Offset(34f, 126f),
            p1 = Offset(68f, 100f),
            p2 = Offset(104f, 137f),
            p3 = Offset(138f, 118f),
            t = s,
        )
    } else {
        val s = (u - 0.52f) / 0.48f
        cubicBezier(
            p0 = Offset(138f, 118f),
            p1 = Offset(170f, 100f),
            p2 = Offset(204f, 130f),
            p3 = Offset(234f, 117f),
            t = s,
        )
    }
}

/**
 * 模拟钢笔尖压感：起笔微顿（2.6f）→ 主峰厚重（3.3f）→ 中腰轻灵（1.8f）→ 尾端出锋（0.9f）。
 */
private fun flourishStrokeWidth(t: Float): Float {
    val u = t.coerceIn(0f, 1f)
    val bell = sin(u * PI.toFloat())
    val wave = sin(u * 2.2f * PI.toFloat()) * 0.45f
    val taper = (1f - u * 0.58f)
    return (1.20f + 1.90f * bell * taper + wave).coerceIn(0.90f, 3.35f)
}

private fun cubicBezier(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
    val inv = 1f - t
    val b0 = inv * inv * inv
    val b1 = 3f * inv * inv * t
    val b2 = 3f * inv * t * t
    val b3 = t * t * t
    return Offset(
        x = b0 * p0.x + b1 * p1.x + b2 * p2.x + b3 * p3.x,
        y = b0 * p0.y + b1 * p1.y + b2 * p2.y + b3 * p3.y,
    )
}

private fun DrawScope.drawFountainPen(
    tip: Offset,
    angleDeg: Float,
    ink: androidx.compose.ui.graphics.Color,
    red: androidx.compose.ui.graphics.Color,
    paper: androidx.compose.ui.graphics.Color,
) {
    withTransform({
        translate(tip.x, tip.y)
        rotate(angleDeg, Offset.Zero)
    }) {
        // 笔握与笔杆（修长圆润的复古钢笔轮廓）
        val barrel = Path().apply {
            moveTo(-4.6f, -23f)
            lineTo(-5.4f, -68f)
            quadraticTo(-5.4f, -74f, 0f, -74f)
            quadraticTo(5.4f, -74f, 5.4f, -68f)
            lineTo(4.6f, -23f)
            close()
        }
        drawPath(barrel, ink)
        // 笔夹细线
        drawLine(
            color = paper.copy(alpha = 0.65f),
            start = Offset(2.3f, -66f),
            end = Offset(2.3f, -38f),
            strokeWidth = 1.1f,
            cap = StrokeCap.Round,
        )
        // 朱红笔环装饰
        drawLine(red, Offset(-4.8f, -27.5f), Offset(4.8f, -27.5f), 2.6f)

        // 钢笔尖（五角翼面 + 呼吸孔 + 中缝）
        val nib = Path().apply {
            moveTo(0f, 0f)
            cubicTo(-1.9f, -5f, -6.5f, -11.2f, -6.5f, -16.2f)
            lineTo(-4.6f, -23f)
            lineTo(4.6f, -23f)
            lineTo(6.5f, -16.2f)
            cubicTo(6.5f, -11.2f, 1.9f, -5f, 0f, 0f)
            close()
        }
        drawPath(nib, paper)
        drawPath(nib, ink, style = Stroke(1.4f, join = StrokeJoin.Round))
        drawLine(ink, Offset(0f, -1.5f), Offset(0f, -13.5f), 1.05f, cap = StrokeCap.Round)
        drawCircle(ink, 1.55f, Offset(0f, -15.2f))
        // 笔尖沾染的墨点
        drawCircle(ink, 1.15f, Offset(0f, -0.8f))
    }
}
