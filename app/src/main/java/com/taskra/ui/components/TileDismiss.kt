package com.taskra.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.TaskraMotion
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 卡片沿对角线分成纸片消散；保存立即开始，列表保留快照直到动效与写入都结束。
 * - 进入消散时通过 GraphicsLayer.toImageBitmap() 仅捕捉 1 次卡片位图纹理，随后 24 片全部用
 *   drawImage 源矩形切块 + 原生 alpha 绘制，零 saveLayer / 零 clipRect 模板切换，中端机 Mali GPU 满帧运行；
 * - 高度收拢采用统一流体缓动，下方卡片顺滑补位零顿挫；
 * - 尊重系统“减少动态效果”：自动收敛为克制的淡出与高度收拢。
 */
@Composable
fun TileDismiss(removing: Boolean, onFinished: () -> Unit, content: @Composable () -> Unit) {
    val reduced = LocalReduceMotion.current
    val progress = remember { Animatable(0f) }
    val finished by rememberUpdatedState(onFinished)
    val layer = rememberGraphicsLayer()
    var recorded by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(removing, reduced) {
        if (removing) {
            if (!reduced) {
                // 等待首帧完成 GraphicsLayer 录制后抓取单张纹理位图
                withFrameNanos { }
                snapshot = runCatching { layer.toImageBitmap() }.getOrNull()
            }
            val duration = if (reduced) 200 else 600
            progress.animateTo(1f, tween(duration, easing = LinearEasing))
            finished()
        } else {
            progress.snapTo(0f)
            recorded = false
            snapshot = null
        }
    }

    Box(Modifier
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val p = progress.value
            val collapseStart = if (reduced) 0.45f else 0.70f
            val rawCollapse = ((p - collapseStart) / (1f - collapseStart)).coerceIn(0f, 1f)
            val collapse = TaskraMotion.FluidOut.transform(rawCollapse)
            layout(placeable.width, (placeable.height * (1f - collapse)).toInt()) {
                placeable.placeRelative(0, 0)
            }
        }
        .drawWithContent {
            val p = progress.value
            if (!removing && p <= 0f) {
                layer.alpha = 1f
                drawContent()
            } else {
                if (!recorded) {
                    layer.alpha = 1f
                    layer.record { this@drawWithContent.drawContent() }
                    recorded = true
                }
                if (reduced) {
                    val fade = 1f - TaskraMotion.Standard.transform((p / 0.65f).coerceIn(0f, 1f))
                    if (fade > 0.005f) {
                        layer.alpha = fade
                        drawLayer(layer)
                    }
                } else {
                    val bmp = snapshot
                    if (bmp == null) {
                        if (p < 0.88f) {
                            layer.alpha = 1f
                            drawLayer(layer)
                        }
                    } else if (p < 0.88f) {
                        val cols = 8
                        val rows = 3
                        val bw = bmp.width
                        val bh = bmp.height
                        if (bw <= 0 || bh <= 0) return@drawWithContent
                        val tileW = bw.toFloat() / cols
                        val tileH = bh.toFloat() / rows
                        val dstTileW = size.width / cols
                        val dstTileH = size.height / rows
                        for (row in 0 until rows) for (col in 0 until cols) {
                            val delay = (col + row) * 0.019f
                            val t = ((p - delay) / 0.64f).coerceIn(0f, 1f)
                            if (t >= 1f) continue
                            val eased = TaskraMotion.Standard.transform(t)
                            val sx = (col * tileW).roundToInt()
                            val sy = (row * tileH).roundToInt()
                            val ex = ((col + 1) * tileW).roundToInt().coerceAtMost(bw)
                            val ey = ((row + 1) * tileH).roundToInt().coerceAtMost(bh)
                            val sw = (ex - sx).coerceAtLeast(1)
                            val sh = (ey - sy).coerceAtLeast(1)
                            val dx = (col * dstTileW).roundToInt()
                            val dy = (row * dstTileH).roundToInt()
                            val dw = (((col + 1) * dstTileW).roundToInt() - dx).coerceAtLeast(1)
                            val dh = (((row + 1) * dstTileH).roundToInt() - dy).coerceAtLeast(1)
                            val center = Offset((col + 0.5f) * dstTileW, (row + 0.5f) * dstTileH)
                            val pulse = if (t < 0.12f) 1f + sin(t / 0.12f * Math.PI).toFloat() * 0.032f else 1f
                            val shrink = (pulse * (1f - eased * eased)).coerceAtLeast(0f)
                            val tileAlpha = (1f - ((t - 0.35f) / 0.65f).coerceIn(0f, 1f))
                            if (shrink <= 0.01f || tileAlpha <= 0.01f) continue
                            withTransform({
                                translate((col - 3.5f) * 9.5f * eased, -36f * eased + row * 7.5f * eased)
                                rotate(((col * 7 + row * 11) % 19 - 9) * eased, center)
                                scale(shrink, shrink, center)
                            }) {
                                drawImage(
                                    image = bmp,
                                    srcOffset = IntOffset(sx, sy),
                                    srcSize = IntSize(sw, sh),
                                    dstOffset = IntOffset(dx, dy),
                                    dstSize = IntSize(dw, dh),
                                    alpha = tileAlpha,
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { content() }
}
