package com.taskra.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import java.time.YearMonth
import com.taskra.data.DeadlineType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** 没有点击语义：每次成功写入都触发反馈，失败则明确显示。 */
@Composable
fun DraftFeedback(state: String, revision: Int) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(revision) {
        if (revision > 0) {
            scale.animateTo(1.18f, tween(160))
            scale.animateTo(1f, tween(240))
        }
    }
    val tint by animateColorAsState(
        if (state == "保存失败") MaterialTheme.colorScheme.error
        else if (state == "草稿已保存") MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "draftTint",
    )
    Box(
        Modifier.size(48.dp).testTag("draft_feedback").semantics {
            contentDescription = "草稿"
            stateDescription = state
            liveRegion = LiveRegionMode.Polite
        },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(targetState = state, label = "draftState") { status ->
            Icon(
                when (status) {
                    "保存失败" -> Icons.Outlined.CloudOff
                    "保存中…" -> Icons.Outlined.CloudUpload
                    else -> Icons.Outlined.CloudDone
                }, null, tint = tint, modifier = Modifier.size(23.dp).scale(scale.value),
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
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(240)) { it / 2 } + fadeIn(tween(180)),
        exit = slideOutVertically(tween(180)) { it / 2 } + fadeOut(tween(140)),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
            AnimatedContent(
                targetState = formatMode,
                transitionSpec = {
                    (fadeIn(tween(180, 60)) + slideInHorizontally(tween(240)) { if (targetState) it / 5 else -it / 5 }) togetherWith
                        (fadeOut(tween(120)) + slideOutHorizontally(tween(200)) { if (targetState) -it / 5 else it / 5 })
                }, label = "editorToolbar",
            ) { formatting ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("editor_toolbar"),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!formatting) {
                        ToolIcon(Icons.Outlined.MicNone, "录音", onRecord)
                        ToolIcon(Icons.Outlined.Image, "相册选择插入", onAlbum)
                        ToolIcon(Icons.Outlined.PhotoCamera, "相机拍照插入", onCamera)
                        if (onPasteImage != null) ToolIcon(Icons.Outlined.ContentPaste, "粘贴图片", onPasteImage)
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
    Surface(shape = CircleShape, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        IconButton(onClick = onClick, modifier = Modifier.semantics { this.selected = selected }) {
            Icon(icon, contentDescription = label, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeadlineDialog(
    date: String?,
    time: String?,
    type: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String?, String?) -> Unit,
) {
    val initialDate = remember { runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now()) }
    val initialTime = remember { runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.of(18, 0)) }
    var selectedDate by remember { mutableStateOf(initialDate) }
    val timeState = rememberTimePickerState(initialTime.hour, initialTime.minute, is24Hour = true)
    var withTime by remember { mutableStateOf(type == DeadlineType.DATETIME) }
    var clockPage by remember { mutableStateOf(false) }
    var dial by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.padding(16.dp).widthIn(max = 360.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (clockPage) TextButton(onClick = { clockPage = false }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("${selectedDate.monthValue}月${selectedDate.dayOfMonth}日")
                    } else Text("截止时间", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "取消截止时间设置") }
                }
                AnimatedContent(targetState = clockPage, label = "deadlinePage") { clock ->
                    if (clock) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (dial) TimePicker(timeState) else TimeInput(timeState)
                            IconButton(onClick = { dial = !dial }) { Icon(if (dial) Icons.Outlined.Keyboard else Icons.Outlined.Schedule, "切换时间输入") }
                        }
                    } else {
                        CompactCalendar(selectedDate, onSelect = { selectedDate = it })
                    }
                }
                if (!clockPage) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { withTime = true; clockPage = true }, modifier = Modifier.weight(1f)) {
                        Text(if (withTime) "%02d:%02d".format(timeState.hour, timeState.minute) else "具体时间")
                    }
                    Switch(checked = withTime, onCheckedChange = { withTime = it; clockPage = it }, modifier = Modifier.testTag("deadline_with_time"))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onConfirm(DeadlineType.NONE, null, null) }) { Text("清除") }
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

/** 用等宽列布局，避免旧版 Material 日期控件在窄屏裁掉周六。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactCalendar(selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    var years by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { years = true }) {
                    Text("${month.year}年${month.monthValue}月", color = MaterialTheme.colorScheme.onSurface)
                    Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = years, onDismissRequest = { years = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                    (month.year - 5..month.year + 10).forEach { year ->
                        DropdownMenuItem(text = { Text("${year}年") }, onClick = { month = month.withYear(year); years = false })
                    }
                }
            }
            IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Outlined.ChevronLeft, "上个月") }
            IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Outlined.ChevronRight, "下个月") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Box(Modifier.weight(1f).height(32.dp), contentAlignment = Alignment.Center) {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val offset = month.atDay(1).dayOfWeek.value - 1
        val rows = (offset + month.lengthOfMonth() + 6) / 7
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            repeat(rows) { week ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { dayOfWeek ->
                        val day = week * 7 + dayOfWeek - offset + 1
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                            if (day in 1..month.lengthOfMonth()) {
                                val date = month.atDay(day)
                                val chosen = date == selected
                                Surface(
                                    onClick = { onSelect(date) }, shape = CircleShape,
                                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                    contentColor = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxSize().semantics { contentDescription = date.toString(); this.selected = chosen },
                                ) {
                                    Box(contentAlignment = Alignment.Center) { Text("$day", style = MaterialTheme.typography.bodyMedium) }
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
