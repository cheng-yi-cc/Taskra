package com.taskra.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

@Composable
fun InkRule(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.height(7.dp)) {
        val stroke = Path().apply {
            moveTo(1f, size.height * .66f)
            cubicTo(size.width * .28f, size.height * .05f, size.width * .67f, size.height * .92f, size.width - 1f, size.height * .3f)
        }
        drawPath(stroke, color, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun NotebookHeading(title: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text("课迹  /  TASKRA", style = MaterialTheme.typography.labelSmall,
            letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge)
        InkRule(Modifier.width(76.dp))
    }
}

@Composable
fun NotebookFab(label: String, onClick: () -> Unit, description: String = label) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
        shape = MaterialTheme.shapes.small,
        containerColor = MaterialTheme.colorScheme.onBackground,
        contentColor = MaterialTheme.colorScheme.background,
        elevation = FloatingActionButtonDefaults.elevation(2.dp, 3.dp, 2.dp, 2.dp),
        icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
        text = { Text(label) },
    )
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
    TopAppBar(title = title, navigationIcon = navigationIcon, actions = actions,
        modifier = modifier.drawWithCache {
            onDrawBehind { drawLine(edge, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun NotebookEmpty(title: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val ink = MaterialTheme.colorScheme.onBackground
        val red = MaterialTheme.colorScheme.primary
        Canvas(Modifier.size(48.dp, 54.dp)) {
            val page = Path().apply {
                moveTo(size.width * .17f, 3f); lineTo(size.width * .87f, 0f)
                lineTo(size.width * .9f, size.height * .87f)
                lineTo(size.width * .12f, size.height * .91f); close()
            }
            drawPath(page, ink, style = Stroke(1.5.dp.toPx()))
            repeat(3) { i ->
                val y = size.height * (.26f + i * .17f)
                drawLine(ink.copy(alpha = .25f), Offset(size.width * .3f, y), Offset(size.width * .72f, y), 1.dp.toPx())
            }
            val check = Path().apply {
                moveTo(size.width * .46f, size.height * .7f)
                lineTo(size.width * .61f, size.height * .8f)
                lineTo(size.width * .98f, size.height * .46f)
            }
            drawPath(check, red, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp))
        }
    }
}
