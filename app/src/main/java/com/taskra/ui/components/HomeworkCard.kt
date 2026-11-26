package com.taskra.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskra.data.DeadlineType
import com.taskra.ui.TodoUiItem
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.TaskraMotion
import com.taskra.ui.motion.applePressScale
import com.taskra.ui.motion.appleTactile
import com.taskra.ui.theme.NotebookHand
import com.taskra.ui.theme.courseColor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeworkCard(
    item: TodoUiItem,
    done: Boolean = false,
    onOpen: () -> Unit,
    onToggleDone: () -> Unit,
    modifier: Modifier = Modifier,
    showCheck: Boolean = true,
) {
    val reduced = LocalReduceMotion.current
    val haptic = LocalHapticFeedback.current
    val cardInteraction = remember { MutableInteractionSource() }
    val checkInteraction = remember { MutableInteractionSource() }

    // 单一完成进度标量驱动对钩、边条色、文字淡化与框线，静止态零额外协程与零重组。
    val doneProgress = remember { Animatable(if (done) 1f else 0f) }
    val boxPop = remember { Animatable(1f) }
    LaunchedEffect(done, reduced) {
        val target = if (done) 1f else 0f
        if (doneProgress.value == target) return@LaunchedEffect
        if (reduced) {
            doneProgress.snapTo(target)
            boxPop.snapTo(1f)
        } else if (done) {
            coroutineScope {
                launch {
                    boxPop.snapTo(0.86f)
                    boxPop.animateTo(1f, TaskraMotion.tactileSpring())
                }
                launch {
                    doneProgress.animateTo(1f, tween(310, easing = TaskraMotion.FluidOut))
                }
            }
        } else {
            doneProgress.animateTo(0f, tween(160, easing = TaskraMotion.Standard))
        }
    }

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
    val doneRuleColor = marker.copy(alpha = if (isDark) 0.72f else 0.62f)
    val activeRuleColor = if (item.overdue) MaterialTheme.colorScheme.error.copy(alpha = 0.85f) else doneRuleColor
    val ink = MaterialTheme.colorScheme.onSurface
    val red = MaterialTheme.colorScheme.primary
    val subtleInk = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .appleTactile(cardInteraction, pressedScale = 0.982f, cornerRadius = 5.dp)
            .semantics { contentDescription = "作业：${item.h.title}" }
            .clickable(interactionSource = cardInteraction, indication = null, onClick = onOpen),
        shape = RoundedCornerShape(5.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
        contentColor = ink,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val p = doneProgress.value
                    val rule = if (p <= 0f) activeRuleColor else lerp(activeRuleColor, doneRuleColor, p)
                    drawLine(
                        color = rule,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 3.dp.toPx(),
                    )
                }
                .padding(start = if (showCheck) 10.dp else 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (showCheck) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleDone()
                    },
                    interactionSource = checkInteraction,
                    modifier = Modifier.applePressScale(checkInteraction, pressedScale = 0.88f),
                ) {
                    // 对钩按进度描出来：先画钩、再让整张卡消散；方框 Path 按尺寸缓存零分配。
                    Box(
                        Modifier
                            .size(25.dp)
                            .graphicsLayer {
                                val s = boxPop.value
                                scaleX = s
                                scaleY = s
                            }
                            .drawWithCache {
                                val boxPath = Path().apply {
                                    moveTo(2.2.dp.toPx(), 3.dp.toPx())
                                    lineTo(size.width - 2.8.dp.toPx(), 1.5.dp.toPx())
                                    lineTo(size.width - 2.dp.toPx(), size.height - 2.8.dp.toPx())
                                    lineTo(2.8.dp.toPx(), size.height - 2.dp.toPx())
                                    close()
                                }
                                val boxStroke = Stroke(1.2.dp.toPx(), join = StrokeJoin.Round)
                                val tickStroke = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                val a = Offset(size.width * .19f, size.height * .48f)
                                val b = Offset(size.width * .43f, size.height * .72f)
                                val c = Offset(size.width * .97f, size.height * .11f)
                                val first = (b - a).getDistance()
                                val second = (c - b).getDistance()
                                val tickPath = Path()
                                onDrawBehind {
                                    val progress = doneProgress.value
                                    val boxAlpha = 0.65f - 0.29f * progress
                                    drawPath(boxPath, ink.copy(alpha = boxAlpha), style = boxStroke)
                                    if (progress > 0f) {
                                        val drawn = (first + second) * progress
                                        tickPath.reset()
                                        tickPath.moveTo(a.x, a.y)
                                        if (drawn <= first) {
                                            val t = if (first > 0f) drawn / first else 0f
                                            tickPath.lineTo(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                                        } else {
                                            tickPath.lineTo(b.x, b.y)
                                            val t = if (second > 0f) ((drawn - first) / second).coerceIn(0f, 1f) else 1f
                                            tickPath.lineTo(b.x + (c.x - b.x) * t, b.y + (c.y - b.y) * t)
                                        }
                                        drawPath(tickPath, red, style = tickStroke)
                                    }
                                }
                            }
                            .semantics { contentDescription = if (done) "恢复为未完成" else "标记完成" }
                    )
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
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = 1f - 0.38f * doneProgress.value },
                )
                if (secondaryText != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        secondaryText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = NotebookHand),
                        color = subtleInk,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = 1f - 0.38f * doneProgress.value },
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
                    if (!showCheck && done) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "已完成",
                                style = MaterialTheme.typography.labelMedium,
                                color = subtleInk,
                            )
                        }
                    }
                    if (item.h.deadlineType != DeadlineType.NONE) {
                        Text(
                            item.dueText + if (item.overdue && !done) " · 已逾期" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (item.overdue && !done) errorColor else subtleInk,
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
                            Icon(Icons.Outlined.Image, null, Modifier.size(14.dp), tint = subtleInk)
                            Text("${item.imageCount}", style = MaterialTheme.typography.labelSmall, color = subtleInk)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionGap() { Spacer(Modifier.height(16.dp)) }
