package com.taskra.ui.components

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 只在进程首次进入时播放；点击可跳过。 */
@Composable
fun OpeningAnimation(onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(1600, easing = LinearEasing))
        finished()
    }
    val p = progress.value
    val ink = MaterialTheme.colorScheme.onBackground
    val red = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.background
    val write = ((p - .08f) / .67f).coerceIn(0f, 1f)
    val opacity = 1f - ((p - .87f) / .13f).coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxSize().testTag("opening_animation")
            .graphicsLayer { alpha = opacity }.notebookPaper()
            .pointerInput(Unit) { detectTapGestures { finished() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(width = 252.dp, height = 156.dp)) {
                withTransform({ scale(size.width / 252f, size.height / 156f, pivot = Offset.Zero) }) {
                    val start = 34f
                    val end = 210f
                    val y = 120f
                    val tip = start + (end - start) * write
                    drawLine(ink, Offset(start, y), Offset(tip, y), 2.2f, cap = StrokeCap.Round)
                    // 笔尖的尖端固定在墨迹终点，整支笔一起平移。
                    withTransform({ translate(tip, y); rotate(30f, Offset.Zero) }) {
                        val barrel = Path().apply {
                            moveTo(-5f, -24f); lineTo(-5f, -69f)
                            quadraticTo(-5f, -73f, 0f, -73f)
                            quadraticTo(5f, -73f, 5f, -69f)
                            lineTo(5f, -24f); close()
                        }
                        drawPath(barrel, ink)
                        drawLine(red, Offset(-5f, -28f), Offset(5f, -28f), 2.5f)
                        val nib = Path().apply {
                            moveTo(0f, 0f); cubicTo(-2f, -5f, -6.4f, -11f, -6.4f, -16f)
                            lineTo(-5f, -24f); lineTo(5f, -24f); lineTo(6.4f, -16f)
                            cubicTo(6.4f, -11f, 2f, -5f, 0f, 0f); close()
                        }
                        drawPath(nib, paper)
                        drawPath(nib, ink, style = Stroke(1.4f, join = StrokeJoin.Round))
                        drawLine(ink, Offset(0f, -1.5f), Offset(0f, -13f), 1f, cap = StrokeCap.Round)
                        drawCircle(ink, 1.5f, Offset(0f, -15f))
                    }
                }
            }
            Text("课迹", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(62.dp))
        }
    }
}
