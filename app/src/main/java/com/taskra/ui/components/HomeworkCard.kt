package com.taskra.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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

    Surface(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "作业：${item.h.title}" }.clickable(onClick = onOpen),
        shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .90f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.Top) {
            val ink = MaterialTheme.colorScheme.onSurface
            val red = MaterialTheme.colorScheme.primary
            IconButton(onClick = onToggleDone) {
                Canvas(Modifier.size(25.dp).semantics { contentDescription = if (done) "恢复为未完成" else "标记完成" }) {
                    val box = Path().apply {
                        moveTo(2.dp.toPx(), 3.dp.toPx()); lineTo(size.width - 3.dp.toPx(), 1.dp.toPx())
                        lineTo(size.width - 2.dp.toPx(), size.height - 3.dp.toPx())
                        lineTo(3.dp.toPx(), size.height - 2.dp.toPx()); close()
                    }
                    drawPath(box, ink.copy(alpha = if (done) .38f else .68f), style = Stroke(1.15.dp.toPx()))
                    if (done) {
                        val tick = Path().apply {
                            moveTo(size.width * .2f, size.height * .47f)
                            lineTo(size.width * .43f, size.height * .71f)
                            lineTo(size.width * .98f, size.height * .1f)
                        }
                        drawPath(tick, red, style = Stroke(2.3.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 2.dp, top = 5.dp, end = 2.dp, bottom = 4.dp)) {
                Text(mainText, style = MaterialTheme.typography.titleMedium.copy(fontFamily = NotebookHand, fontSize = 19.sp, lineHeight = 25.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (secondaryText != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(secondaryText, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = NotebookHand),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(5.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.widthIn(max = 142.dp).clip(RoundedCornerShape(2.dp))
                        .background(marker.copy(alpha = if (isDark) .25f else .17f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(item.courseName ?: "未分类", style = MaterialTheme.typography.labelMedium,
                            color = markerText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (item.h.deadlineType != DeadlineType.NONE) {
                        Text(item.dueText + if (item.overdue && !done) " · 已逾期" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (item.overdue && !done) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (item.imageCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
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
