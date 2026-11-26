package com.taskra.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.taskra.data.DeadlineType
import com.taskra.ui.motion.AppleDialogContainer
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.TaskraMotion
import com.taskra.ui.motion.appleContentSize
import com.taskra.ui.motion.applePressScale
import com.taskra.ui.motion.errorShake
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** 没有点击语义：每次成功写入都触发弹簧确认反馈，保存中轻呼吸，失败则水平微震并明确标红。 */
@Composable
fun DraftFeedback(state: String, revision: Int) {
    val reduced = LocalReduceMotion.current
    val scale = remember { Animatable(1f) }
    var failShake by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision, reduced) {
        if (revision > 0 && !reduced) {
            scale.snapTo(1.18f)
            scale.animateTo(1f, TaskraMotion.tactileSpring())
        }
    }
    LaunchedEffect(state) {
        if (state == "保存失败") failShake++
    }
    val savingPulseAlpha = if (state == "保存中…" && !reduced) {
        val infinite = rememberInfiniteTransition(label = "draftSavingPulse")
        infinite.animateFloat(
            initialValue = 0.52f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(680, easing = TaskraMotion.Standard),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "draftPulseAlpha",
        ).value
    } else 1f
    val tint by animateColorAsState(
        targetValue = when (state) {
            "保存失败" -> MaterialTheme.colorScheme.error
            "草稿已保存" -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = TaskraMotion.colorSpec(reduced, TaskraMotion.StandardMs),
        label = "draftTint",
    )
    Box(
        Modifier
            .size(48.dp)
            .errorShake(failShake)
            .testTag("draft_feedback")
            .semantics {
                contentDescription = "草稿"
                stateDescription = state
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = { TaskraMotion.iconSwapSpec(reduced) },
            label = "draftState",
        ) { status ->
            Icon(
                imageVector = when (status) {
                    "保存失败" -> Icons.Outlined.CloudOff
                    "保存中…" -> Icons.Outlined.CloudUpload
                    else -> Icons.Outlined.CloudDone
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size(23.dp)
                    .graphicsLayer {
                        val s = scale.value
                        scaleX = s
                        scaleY = s
                        alpha = savingPulseAlpha
                    },
            )
        }
    }
}

@Composable
fun EditorToolbar(
    visible: Boolean,
    formatMode: Boolean,
    onFormatMode: (Boolean) -> Unit,
    onRecord: () -> Unit,
    onAlbum: () -> Unit,
    onCamera: () -> Unit,
    bold: Boolean,
    highlight: Boolean,
    sizeLevel: Int,
    onBold: () -> Unit,
    onHighlight: () -> Unit,
    onSize: (Int) -> Unit,
    onPasteImage: (() -> Unit)? = null,
) {
    val reduced = LocalReduceMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) fadeIn(tween(TaskraMotion.ReducedFadeMs))
        else slideInVertically(tween(TaskraMotion.StandardMs, easing = TaskraMotion.FluidOut)) { it / 2 } +
            fadeIn(tween(TaskraMotion.QuickMs, easing = TaskraMotion.Decelerate)),
        exit = if (reduced) fadeOut(tween(TaskraMotion.ReducedFadeMs))
        else slideOutVertically(tween(170, easing = TaskraMotion.Standard)) { it / 2 } +
            fadeOut(tween(130, easing = TaskraMotion.Standard)),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
            AnimatedContent(
                targetState = formatMode,
                transitionSpec = {
                    TaskraMotion.directionalSlideSpec(
                        forward = targetState,
                        reduced = reduced,
                        offsetFraction = 0.16f,
                    )
                },
                label = "editorToolbar",
            ) { formatting ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("editor_toolbar"),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!formatting) {
                        ToolIcon(Icons.Outlined.MicNone, "录音", onRecord)
                        ToolIcon(Icons.Outlined.Image, "相册选择插入", onAlbum)
                        ToolIcon(Icons.Outlined.PhotoCamera, "相机拍照插入", onCamera)
                        AnimatedVisibility(
                            visible = onPasteImage != null,
                            enter = TaskraMotion.popEnter(reduced),
                            exit = TaskraMotion.popExit(reduced),
                        ) {
                            ToolIcon(Icons.Outlined.ContentPaste, "粘贴图片", { onPasteImage?.invoke() })
                        }
                        ToolIcon(Icons.Outlined.TextFields, "文字格式", { onFormatMode(true) })
                    } else {
                        ToolIcon(Icons.Outlined.ArrowBack, "返回工具栏", { onFormatMode(false) })
                        ToolIcon(Icons.Outlined.FormatBold, "加粗", onBold, bold)
                        ToolIcon(Icons.Outlined.BorderColor, "背景高亮", onHighlight, highlight)
                        Box {
                            var menu by remember { mutableStateOf(false) }
                            ToolIcon(Icons.Outlined.FormatSize, "字号", { menu = true }, sizeLevel != 0)
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, properties = PopupProperties(focusable = false)) {
                                listOf(0 to "正文", 3 to "中号", 2 to "大号", 1 to "特大").forEach { (level, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        leadingIcon = { if (level == sizeLevel) Icon(Icons.Outlined.Check, null) },
                                        onClick = { onSize(level); menu = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, label: String, onClick: () -> Unit, selected: Boolean = false) {
    val reduced = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        animationSpec = TaskraMotion.colorSpec(reduced, TaskraMotion.QuickMs),
        label = "toolIconBg",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        animationSpec = TaskraMotion.colorSpec(reduced, TaskraMotion.QuickMs),
        label = "toolIconTint",
    )
    Surface(
        shape = CircleShape,
        color = bg,
        modifier = Modifier.applePressScale(interaction, pressedScale = 0.88f),
    ) {
        IconButton(
            onClick = onClick,
            interactionSource = interaction,
            modifier = Modifier.semantics { this.selected = selected },
        ) {
            Icon(icon, contentDescription = label, tint = tint)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeadlineDialog(
    date: String?,
    time: String?,
    type: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String?, String?) -> Unit,
) {
    val reduced = LocalReduceMotion.current
    val today = remember { LocalDate.now() }
    val initialDate = remember { runCatching { LocalDate.parse(date) }.getOrDefault(today) }
    val initialTime = remember { runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.of(23, 59)) }
    var selectedDate by remember { mutableStateOf(initialDate) }
    val timeState = rememberTimePickerState(initialTime.hour, initialTime.minute, is24Hour = true)
    var withTime by remember { mutableStateOf(type == DeadlineType.DATETIME) }
    var clockPage by remember { mutableStateOf(false) }
    var dial by remember { mutableStateOf(true) }
    val quickDates = remember(today) {
        val nextMon = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        val thisOrNextFri = today.with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
        val nextFri = if (thisOrNextFri == today.plusDays(1)) thisOrNextFri.plusWeeks(1) else thisOrNextFri
        buildList {
            add("今天" to today)
            add("明天" to today.plusDays(1))
            if (today.dayOfWeek == DayOfWeek.SUNDAY) {
                add("本周五" to thisOrNextFri)
                add("下周日" to today.plusWeeks(1))
            } else if (nextFri.isBefore(nextMon)) {
                add("本周五" to nextFri)
                add("下周一" to nextMon)
            } else {
                add("下周一" to nextMon)
                add("下周五" to nextFri)
            }
        }.distinctBy { it.second }
    }
    val quickTimes = remember {
        listOf(12 to 0, 18 to 0, 22 to 0, 23 to 59)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AppleDialogContainer(modifier = Modifier.padding(16.dp).widthIn(max = 360.dp).fillMaxWidth()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp).appleContentSize()) {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AnimatedContent(
                            targetState = clockPage,
                            transitionSpec = { TaskraMotion.crossfadeSpec(reduced) },
                            modifier = Modifier.weight(1f),
                            label = "deadlineHeader",
                        ) { clock ->
                            if (clock) {
                                TextButton(onClick = { clockPage = false }) {
                                    Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("${selectedDate.monthValue}月${selectedDate.dayOfMonth}日")
                                }
                            } else {
                                Text("截止时间", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "取消截止时间设置") }
                    }
                    AnimatedContent(
                        targetState = clockPage,
                        transitionSpec = {
                            TaskraMotion.directionalSlideSpec(
                                forward = targetState,
                                reduced = reduced,
                                offsetFraction = 0.16f,
                            )
                        },
                        label = "deadlinePage",
                    ) { clock ->
                        if (clock) {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    quickTimes.forEach { (h, m) ->
                                        FilterChip(
                                            selected = timeState.hour == h && timeState.minute == m,
                                            onClick = {
                                                timeState.hour = h
                                                timeState.minute = m
                                            },
                                            label = { Text("%02d:%02d".format(h, m)) },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                AnimatedContent(
                                    targetState = dial,
                                    transitionSpec = { TaskraMotion.crossfadeSpec(reduced) },
                                    label = "clockInputMode",
                                ) { useDial ->
                                    if (useDial) TimePicker(timeState) else TimeInput(timeState)
                                }
                                IconButton(onClick = { dial = !dial }) {
                                    AnimatedContent(
                                        targetState = dial,
                                        transitionSpec = { TaskraMotion.iconSwapSpec(reduced) },
                                        label = "clockModeIcon",
                                    ) { useDial ->
                                        Icon(if (useDial) Icons.Outlined.Keyboard else Icons.Outlined.Schedule, "切换时间输入")
                                    }
                                }
                            }
                        } else {
                            Column(Modifier.fillMaxWidth()) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    quickDates.forEach { (label, d) ->
                                        FilterChip(
                                            selected = selectedDate == d,
                                            onClick = { selectedDate = d },
                                            label = { Text(label) },
                                        )
                                    }
                                }
                                CompactCalendar(selectedDate, today = today, onSelect = { selectedDate = it })
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = !clockPage,
                        enter = TaskraMotion.expandEnter(reduced),
                        exit = TaskraMotion.collapseExit(reduced),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Schedule, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { withTime = true; clockPage = true }, modifier = Modifier.weight(1f)) {
                                Text(if (withTime) "%02d:%02d".format(timeState.hour, timeState.minute) else "具体时间")
                            }
                            Switch(checked = withTime, onCheckedChange = { withTime = it; clockPage = it }, modifier = Modifier.testTag("deadline_with_time"))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (type != DeadlineType.NONE) {
                            TextButton(onClick = { onConfirm(DeadlineType.NONE, null, null) }) { Text("清除") }
                        }
                        Spacer(Modifier.weight(1f))
                        FilledTonalButton(
                            onClick = {
                                val selected = selectedDate.toString()
                                onConfirm(if (withTime) DeadlineType.DATETIME else DeadlineType.DATE, selected,
                                    if (withTime) "%02d:%02d".format(timeState.hour, timeState.minute) else null)
                            }, modifier = Modifier.testTag("deadline_confirm"),
                        ) { Text("确定") }
                    }
                }
            }
        }
    }
}

/** 用等宽列布局，避免旧版 Material 日期控件在窄屏裁掉周六；翻月按方向平滑滑入。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactCalendar(selected: LocalDate, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    val reduced = LocalReduceMotion.current
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    LaunchedEffect(selected) {
        val target = YearMonth.from(selected)
        if (month != target) month = target
    }
    var years by remember { mutableStateOf(false) }
    val yearArrowState = animateFloatAsState(
        targetValue = if (years) 180f else 0f,
        animationSpec = if (reduced) tween(0) else TaskraMotion.spatialSpring(),
        label = "yearDropdownArrow",
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { years = true }) {
                    AnimatedContent(
                        targetState = month,
                        transitionSpec = { TaskraMotion.crossfadeSpec(reduced) },
                        label = "calendarMonthTitle",
                    ) { m ->
                        Text("${m.year}年${m.monthValue}月", color = MaterialTheme.colorScheme.onSurface)
                    }
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = yearArrowState.value },
                    )
                }
                DropdownMenu(expanded = years, onDismissRequest = { years = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                    (month.year - 5..month.year + 10).forEach { year ->
                        DropdownMenuItem(text = { Text("${year}年") }, onClick = { month = month.withYear(year); years = false })
                    }
                }
            }
            val prevInteraction = remember { MutableInteractionSource() }
            val nextInteraction = remember { MutableInteractionSource() }
            IconButton(
                onClick = { month = month.minusMonths(1) },
                interactionSource = prevInteraction,
                modifier = Modifier.applePressScale(prevInteraction, 0.88f),
            ) { Icon(Icons.Outlined.ChevronLeft, "上个月") }
            IconButton(
                onClick = { month = month.plusMonths(1) },
                interactionSource = nextInteraction,
                modifier = Modifier.applePressScale(nextInteraction, 0.88f),
            ) { Icon(Icons.Outlined.ChevronRight, "下个月") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Box(Modifier.weight(1f).height(32.dp), contentAlignment = Alignment.Center) {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        AnimatedContent(
            targetState = month,
            transitionSpec = {
                TaskraMotion.directionalSlideSpec(
                    forward = targetState > initialState,
                    reduced = reduced,
                    offsetFraction = 0.22f,
                )
            },
            label = "calendarMonthGrid",
        ) { targetMonth ->
            val offset = targetMonth.atDay(1).dayOfWeek.value - 1
            val rows = (offset + targetMonth.lengthOfMonth() + 6) / 7
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Column(Modifier.fillMaxWidth()) {
                    repeat(rows) { week ->
                        Row(Modifier.fillMaxWidth()) {
                            repeat(7) { dayOfWeek ->
                                val day = week * 7 + dayOfWeek - offset + 1
                                Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                                    if (day in 1..targetMonth.lengthOfMonth()) {
                                        val date = targetMonth.atDay(day)
                                        val chosen = date == selected
                                        val isToday = date == today
                                        val dayInteraction = remember { MutableInteractionSource() }
                                        val bg by animateColorAsState(
                                            targetValue = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                            animationSpec = TaskraMotion.colorSpec(reduced, TaskraMotion.QuickMs),
                                            label = "dayBg",
                                        )
                                        val fg by animateColorAsState(
                                            targetValue = when {
                                                chosen -> MaterialTheme.colorScheme.onPrimary
                                                isToday -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                            animationSpec = TaskraMotion.colorSpec(reduced, TaskraMotion.QuickMs),
                                            label = "dayFg",
                                        )
                                        Surface(
                                            onClick = { onSelect(date) },
                                            interactionSource = dayInteraction,
                                            shape = CircleShape,
                                            color = bg,
                                            contentColor = fg,
                                            border = if (isToday && !chosen) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .applePressScale(dayInteraction, pressedScale = 0.88f)
                                                .semantics { contentDescription = date.toString(); this.selected = chosen },
                                        ) {
                                            Box(contentAlignment = Alignment.Center) { Text("$day", style = MaterialTheme.typography.bodyMedium) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
