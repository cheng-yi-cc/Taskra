package com.taskra.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.TaskraMotion
import com.taskra.ui.motion.applePressScale
import kotlin.math.roundToInt

/**
 * 方格纸底纹：将纸底与 24dp 网格合烘为一张不透明 24dp×24dp 单元纹理，经 GPU ImageShader 单次平铺绘制。
 * 无矢量 Path 细分、无全屏 ColorFilter 混合，长列表滚动零额外开销。
 */
@Composable
fun Modifier.notebookPaper(): Modifier {
    val density = LocalDensity.current
    val stepPx = with(density) { 24.dp.toPx().roundToInt().coerceAtLeast(1) }
    val strokePx = with(density) { 0.5.dp.toPx().coerceAtLeast(1f) }
    val paper = MaterialTheme.colorScheme.background
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)
    val tileBrush = remember(stepPx, strokePx, paper, grid) {
        val bmp = ImageBitmap(stepPx, stepPx, ImageBitmapConfig.Argb8888)
        val c = ComposeCanvas(bmp)
        val bgPaint = Paint().apply { color = paper }
        c.drawRect(0f, 0f, stepPx.toFloat(), stepPx.toFloat(), bgPaint)
        val linePaint = Paint().apply {
            color = grid
            isAntiAlias = false
        }
        c.drawRect(0f, 0f, strokePx, stepPx.toFloat(), linePaint)
        c.drawRect(0f, 0f, stepPx.toFloat(), strokePx, linePaint)
        ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
    }
    return drawBehind {
        drawRect(brush = tileBrush, size = Size(size.width, size.height))
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

/** 加号悬浮按钮：只用图标，颜色跟随主题（朱红底 + 反差图标色），按下带轻盈弹性回弹。 */
@Composable
fun NotebookFab(
    description: String,
    onClick: () -> Unit,
    icon: ImageVector = Icons.Outlined.Add,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    FloatingActionButton(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier
            .applePressScale(interaction, pressedScale = 0.91f)
            .semantics { contentDescription = description },
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(2.dp, 4.dp, 4.dp, 3.dp),
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

/** 纸片感空状态卡片：手绘微倾便签纸 + 朱红对钩（出现时轻柔落纸并一笔描出对钩）。 */
@Composable
fun NotebookEmpty(title: String, modifier: Modifier = Modifier) {
    val reduced = LocalReduceMotion.current
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    val checkDraw = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) {
            appear.animateTo(1f, tween(TaskraMotion.StandardMs, easing = TaskraMotion.FluidOut))
            checkDraw.animateTo(1f, tween(260, easing = TaskraMotion.FluidOut))
        }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val p = appear.value
                alpha = p
                if (!reduced) translationY = (1f - p) * 12.dp.toPx()
            },
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
                val t = checkDraw.value
                if (t > 0f) {
                    val a = Offset(size.width * .45f, size.height * .69f)
                    val b = Offset(size.width * .60f, size.height * .80f)
                    val c = Offset(size.width * .96f, size.height * .45f)
                    val seg1 = (b - a).getDistance()
                    val seg2 = (c - b).getDistance()
                    val drawn = (seg1 + seg2) * t
                    val check = Path().apply {
                        moveTo(a.x, a.y)
                        if (drawn <= seg1) {
                            val f = if (seg1 > 0f) drawn / seg1 else 0f
                            lineTo(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
                        } else {
                            lineTo(b.x, b.y)
                            val f = if (seg2 > 0f) ((drawn - seg1) / seg2).coerceIn(0f, 1f) else 1f
                            lineTo(b.x + (c.x - b.x) * f, b.y + (c.y - b.y) * f)
                        }
                    }
                    drawPath(check, red, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
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
