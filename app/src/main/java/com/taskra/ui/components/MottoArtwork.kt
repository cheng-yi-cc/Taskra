package com.taskra.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** 一笔手写下划线与右下角小星芒，不参与触控和无障碍朗读。 */
fun Modifier.mottoArtwork(ink: Color) = drawBehind {
    val left = 18.dp.toPx()
    val y = size.height - 11.dp.toPx()
    val line = Path().apply {
        moveTo(left, y)
        cubicTo(left + 21.dp.toPx(), y - 4.dp.toPx(), left + 43.dp.toPx(), y + 2.dp.toPx(), left + 68.dp.toPx(), y - 2.dp.toPx())
    }
    drawPath(line, ink.copy(alpha = 0.48f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
    val center = Offset(size.width - 24.dp.toPx(), size.height - 20.dp.toPx())
    for (i in 0..3) {
        val angle = i * Math.PI / 4
        val ray = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat()) * 5.dp.toPx()
        drawLine(ink.copy(alpha = 0.45f), center - ray, center + ray, strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
    }
}
