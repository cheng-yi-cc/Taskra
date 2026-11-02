package com.taskra.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.layout
import kotlin.math.sin

/** 卡片沿对角线分成纸片消散；保存立即开始，列表保留快照直到动效与写入都结束。 */
@Composable
fun TileDismiss(removing: Boolean, onFinished: () -> Unit, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(removing) {
        if (removing) {
            progress.animateTo(1f, tween(620, easing = LinearEasing))
            finished()
        } else progress.snapTo(0f)
    }
    Box(Modifier
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val collapse = ((progress.value - 0.76f) / 0.24f).coerceIn(0f, 1f)
            layout(placeable.width, (placeable.height * (1f - collapse)).toInt()) {
                placeable.placeRelative(0, 0)
            }
        }
        .drawWithContent {
            val p = progress.value
            if (p == 0f) {
                drawContent()
            } else if (p < 0.88f) {
                val cols = 8
                val rows = 3
                val w = size.width / cols
                val h = size.height / rows
                for (row in 0 until rows) for (col in 0 until cols) {
                    val delay = (col + row) * 0.019f
                    val t = ((p - delay) / 0.65f).coerceIn(0f, 1f)
                    if (t >= 1f) continue
                    val center = Offset((col + 0.5f) * w, (row + 0.5f) * h)
                    val pulse = if (t < 0.12f) 1f + sin(t / 0.12f * Math.PI).toFloat() * 0.035f else 1f
                    val shrink = pulse * (1f - t * t)
                    withTransform({
                        translate((col - 3.5f) * 9f * t, -36f * t + row * 8f * t)
                        rotate(((col * 7 + row * 11) % 19 - 9) * t, center)
                        scale(shrink, shrink, center)
                        clipRect(col * w, row * h, (col + 1) * w, (row + 1) * h)
                    }) { this@drawWithContent.drawContent() }
                }
            }
        }
    ) { content() }
}
