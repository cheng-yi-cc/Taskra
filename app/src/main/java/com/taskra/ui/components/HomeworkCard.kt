package com.taskra.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskra.data.DeadlineType
import com.taskra.ui.TodoUiItem
import com.taskra.ui.theme.NotebookHand
import com.taskra.ui.theme.courseColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeworkCard(
    item: TodoUiItem,
    done: Boolean = false,
    onOpen: () -> Unit,
    onToggleDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = item.h.title.ifBlank { "未命名作业" }
    val summary = item.summary.trim()
    // 自动标题是正文前 30 字，展示更完整的摘要；历史手写标题仍然保留。
    val titlePrefix = title.removeSuffix("…").trim()
    val summaryIncludesTitle = summary.isNotBlank() && titlePrefix.isNotBlank() && summary.startsWith(titlePrefix)
    val mainText = if (summaryIncludesTitle) summary else title
    val secondaryText = summary.takeIf { it.isNotBlank() && !summaryIncludesTitle && it != title }
    val marker = courseColor(item.courseColor)
    val isDark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val markerText = lerp(marker, MaterialTheme.colorScheme.onSurface, if (isDark) .42f else .28f)
    val sideRuleColor = if (item.overdue && !done) MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
    else marker.copy(alpha = if (isDark) 0.72f else 0.62f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "作业：${item.h.title}" }
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(5.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawLine(
                        color = sideRuleColor,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 3.dp.toPx(),
                    )
                }
                .padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            val ink = MaterialTheme.colorScheme.onSurface
            val red = MaterialTheme.colorScheme.primary
            IconButton(onClick = onToggleDone) {
                // 对钩按进度描出来：先画钩、再让整张卡消散（待办页的顺序）。
                val tick = remember { Animatable(if (done) 1f else 0f) }
                LaunchedEffect(done) {
                    if (done) tick.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) else tick.snapTo(0f)
                }
                Canvas(Modifier.size(25.dp).semantics { contentDescription = if (done) "恢复为未完成" else "标记完成" }) {
                    val box = Path().apply {
                        moveTo(2.2.dp.toPx(), 3.dp.toPx())
                        lineTo(size.width - 2.8.dp.toPx(), 1.5.dp.toPx())
                        lineTo(size.width - 2.dp.toPx(), size.height - 2.8.dp.toPx())
                        lineTo(2.8.dp.toPx(), size.height - 2.dp.toPx())
                        close()
                    }
                    drawPath(
                        box,
                        ink.copy(alpha = if (done) .36f else .65f),
                        style = Stroke(1.2.dp.toPx(), join = StrokeJoin.Round),
                    )
                    val progress = tick.value
                    if (progress > 0f) {
                        val a = Offset(size.width * .19f, size.height * .48f)
                        val b = Offset(size.width * .43f, size.height * .72f)
                        val c = Offset(size.width * .97f, size.height * .11f)
                        val first = (b - a).getDistance()
                        val second = (c - b).getDistance()
                        val drawn = (first + second) * progress
                        val tickPath = Path().apply {
                            moveTo(a.x, a.y)
                            if (drawn <= first) {
                                val t = if (first > 0f) drawn / first else 0f
                                lineTo(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                            } else {
                                lineTo(b.x, b.y)
                                val t = if (second > 0f) ((drawn - first) / second).coerceIn(0f, 1f) else 1f
                                lineTo(b.x + (c.x - b.x) * t, b.y + (c.y - b.y) * t)
                            }
                        }
                        drawPath(
                            tickPath,
                            red,
                            style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 2.dp, top = 4.dp, end = 2.dp, bottom = 3.dp)) {
                Text(
                    mainText,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = NotebookHand,
                        fontSize = 19.sp,
                        lineHeight = 25.sp,
                    ),
                    color = ink.copy(alpha = if (done) 0.68f else 1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (secondaryText != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        secondaryText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = NotebookHand),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(7.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier
                            .widthIn(max = 152.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(marker.copy(alpha = if (isDark) .25f else .16f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            item.courseName ?: "未分类",
                            style = MaterialTheme.typography.labelMedium,
                            color = markerText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (item.h.deadlineType != DeadlineType.NONE) {
                        Text(
                            item.dueText + if (item.overdue && !done) " · 已逾期" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (item.overdue && !done) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                    if (item.imageCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.align(Alignment.CenterVertically),
                        ) {
                            Icon(Icons.Outlined.Image, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${item.imageCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionGap() { Spacer(Modifier.height(16.dp)) }
