package com.taskra.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 静止的纸格共用一层绘制，不用位图、不在重组时生成随机噪点。 */
@Composable
fun Modifier.notebookPaper(): Modifier {
    val paper = MaterialTheme.colorScheme.background
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)
    return background(paper).drawWithCache {
        val step = 24.dp.toPx()
        val lines = Path().apply {
            var x = 0f
            while (x <= size.width) { moveTo(x, 0f); lineTo(x, size.height); x += step }
            var y = 0f
            while (y <= size.height) { moveTo(0f, y); lineTo(size.width, y); y += step }
        }
        onDrawBehind { drawPath(lines, grid, style = Stroke(0.5.dp.toPx())) }
    }
}

/** 标题下方的手写游丝墨线，尾端带一枚朱砂收笔点。 */
@Composable
fun InkRule(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.height(9.dp)) {
        val endX = (size.width - 7.dp.toPx()).coerceAtLeast(8f)
        val stroke = Path().apply {
            moveTo(1f, size.height * .68f)
            cubicTo(
                size.width * .26f, size.height * .08f,
                size.width * .62f, size.height * .92f,
                endX, size.height * .34f,
            )
        }
        drawPath(stroke, color, style = Stroke(1.85.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(
            color = color.copy(alpha = 0.85f),
            radius = 1.6.dp.toPx(),
            center = Offset(size.width - 2.dp.toPx(), size.height * .32f),
        )
    }
}

@Composable
fun NotebookHeading(title: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 6.dp, bottom = 4.dp)) {
        Text(
            "课迹  /  TASKRA",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 2.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(2.dp))
        InkRule(Modifier.width(84.dp))
    }
}

/** 加号悬浮按钮：只用图标，颜色跟随主题（朱红底 + 反差图标色）。 */
@Composable
fun NotebookFab(
    description: String,
    onClick: () -> Unit,
    icon: ImageVector = Icons.Outlined.Add,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(2.dp, 3.dp, 2.dp, 2.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val edge = MaterialTheme.colorScheme.outlineVariant
    TopAppBar(
        title = title,
        navigationIcon = navigationIcon,
        actions = actions,
        modifier = modifier.drawWithCache {
            onDrawBehind {
                drawLine(edge, Offset(0f, size.height), Offset(size.width, size.height), 0.8.dp.toPx())
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** 纸片感空状态卡片：手绘微倾便签纸 + 朱红对钩。 */
@Composable
fun NotebookEmpty(title: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(5.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val ink = MaterialTheme.colorScheme.onSurfaceVariant
            val red = MaterialTheme.colorScheme.primary
            Canvas(Modifier.size(38.dp, 42.dp)) {
                val page = Path().apply {
                    moveTo(size.width * .17f, 3f)
                    lineTo(size.width * .87f, 0f)
                    lineTo(size.width * .90f, size.height * .87f)
                    lineTo(size.width * .12f, size.height * .91f)
                    close()
                }
                drawPath(page, ink.copy(alpha = 0.72f), style = Stroke(1.3.dp.toPx(), join = StrokeJoin.Round))
                repeat(3) { i ->
                    val y = size.height * (.27f + i * .17f)
                    drawLine(
                        ink.copy(alpha = .28f),
                        Offset(size.width * .28f, y),
                        Offset(size.width * .72f, y),
                        1.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                val check = Path().apply {
                    moveTo(size.width * .45f, size.height * .69f)
                    lineTo(size.width * .60f, size.height * .80f)
                    lineTo(size.width * .96f, size.height * .45f)
                }
                drawPath(check, red, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
