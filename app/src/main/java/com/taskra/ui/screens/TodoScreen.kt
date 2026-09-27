package com.taskra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.taskra.TaskraApp
import com.taskra.data.SortMode
import com.taskra.data.Status
import com.taskra.ui.AppViewModel
import com.taskra.ui.TodoUiItem
import com.taskra.ui.components.HomeworkCard
import com.taskra.ui.filterTodo
import com.taskra.util.HomeworkSort
import com.taskra.util.TimeUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TodoScreen(
    app: TaskraApp,
    vm: AppViewModel,
    onOpenHomework: (String) -> Unit,
    onNewHomework: () -> Unit,
    onOpenDrafts: () -> Unit,
) {
    val prefs by vm.prefs.collectAsState()
    val todoAll by vm.todoAll.collectAsState()
    val courses by vm.coursesAll.collectAsState()
    val semesters by vm.semesters.collectAsState()
    val query by vm.query.collectAsState()
    val filterCourse by vm.filterCourseId.collectAsState()
    val filterDue by vm.filterDue.collectAsState()
    val filterSem by vm.filterSemesterId.collectAsState()
    val drafts by app.repo.observeDrafts().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 回到前台时刷新（逾期派生状态重算）：重组即重算 + 监听 resume 触发一次重组
    var resumeTick by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        val ob = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        owner.lifecycle.addObserver(ob)
    }
    @Suppress("UNUSED_EXPRESSION") resumeTick

    val courseMap = remember(courses) { courses.associateBy { it.id } }
    // 摘要：从数据库按需查（首版简化：标题+课程名搜索；正文摘要异步加载后参与搜索需重查——此处用标题+课程名即时过滤，
    // 全文搜索在输入后触发一次 DB 扫描）
    var summaries by remember { mutableStateOf<Map<String, Pair<String, Int>>>(emptyMap()) }
    var searchTexts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(todoAll) {
        // 轻量：只取每个作业的文本摘要与图片数（不解码原图）
        // 搜索范围：标题、题目与解答文字、课程名称（不含图片文字）
        val map = mutableMapOf<String, Pair<String, Int>>()
        val search = mutableMapOf<String, String>()
        todoAll.forEach { h ->
            try {
                val bl = app.repo.db.blockDao().listByHomework(h.id)
                val texts = bl.filter { it.kind == "TEXT" }
                    .mapNotNull { it.text }.filter { it.isNotBlank() }
                val first = texts.firstOrNull()?.take(80) ?: ""
                val imgs = bl.count { it.kind == "IMAGE" }
                val label = if (first.isBlank() && imgs > 0) "图片作业" else first
                map[h.id] = label to imgs
                search[h.id] = (listOf(h.title) + texts).joinToString("\n")
            } catch (_: Exception) { }
        }
        summaries = map
        searchTexts = search
    }

    val filtered = remember(todoAll, courses, query, filterCourse, filterDue, filterSem, summaries, searchTexts) {
        filterTodo(todoAll, courseMap, query, filterCourse, filterDue, filterSem, summaries, searchTexts)
    }
    val sorted = remember(filtered, prefs.sortMode) {
        HomeworkSort.sort(filtered, prefs.sortMode)
    }
    val todoItems = remember(sorted) {
        sorted.map { h ->
            val c = h.courseId?.let { courseMap[it] }
            val overdue = TimeUtils.isOverdue(h.deadlineType, h.deadlineDate, h.deadlineTime, h.deadlineZoneId)
            val (label, imgs) = summaries[h.id] ?: ("" to 0)
            TodoUiItem(
                h = h,
                courseName = c?.name,
                courseColor = c?.colorIndex ?: 0,
                overdue = overdue,
                dueText = TimeUtils.formatDeadline(h.deadlineType, h.deadlineDate, h.deadlineTime, h.deadlineZoneId),
                summary = label,
                imageCount = imgs,
            )
        }
    }
    val overdueCount = remember(todoAll) {
        todoAll.count {
            it.status == Status.TODO && TimeUtils.isOverdue(it.deadlineType, it.deadlineDate, it.deadlineTime, it.deadlineZoneId)
        }
    }
    val filtering = query.isNotBlank() || filterCourse != null || filterDue != "ALL" || filterSem != null
    val filterActive = filterCourse != null || filterDue != "ALL" || filterSem != null

    var showMottoEdit by remember { mutableStateOf(false) }
    var mottoDraft by remember { mutableStateOf(prefs.mottoText) }
    var mottoExpanded by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewHomework,
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "新建作业")
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(pad)
                .testTag("todo_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 标题与搜索入口
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("待办", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Outlined.Search, contentDescription = "搜索")
                    }
                }
                if (showSearch) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { vm.query.value = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索标题、题目、解答、课程名") },
                        singleLine = true,
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { vm.query.value = "" }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                                }
                            }
                        },
                    )
                    Text(
                        "仅搜索文字，不识别图片中的文字",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 给自己的话
            if (prefs.mottoVisible) {
                item {
                    MottoCard(
                        text = prefs.mottoText,
                        expanded = mottoExpanded,
                        onToggleExpand = { mottoExpanded = !mottoExpanded },
                        onEdit = { mottoDraft = prefs.mottoText; showMottoEdit = true },
                    )
                }
            }

            // 待办数量和截止概览
            item {
                val label = if (filtering) "筛选结果 ${todoItems.size} 项（共 ${todoAll.size} 项待办）"
                else "共 ${todoAll.size} 项待办" + if (overdueCount > 0) "，其中 $overdueCount 项已逾期" else ""
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (drafts.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "有 ${drafts.size} 份草稿未提交 →",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onOpenDrafts),
                    )
                }
            }

            // 筛选与排序
            item {
                // 课程筛选
                Text("课程", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = filterCourse == null,
                        onClick = { vm.filterCourseId.value = null },
                        label = { Text("全部课程") },
                    )
                    FilterChip(
                        selected = filterCourse == "UNCAT",
                        onClick = { vm.filterCourseId.value = "UNCAT" },
                        label = { Text("未分类") },
                    )
                    courses.forEach { c ->
                        FilterChip(
                            selected = filterCourse == c.id,
                            onClick = { vm.filterCourseId.value = c.id },
                            label = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("截止状态", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val opts = listOf(
                        "ALL" to "全部", "OVERDUE" to "已逾期", "TODAY" to "今天截止",
                        "WEEK" to "未来七天", "NONE" to "无截止时间",
                    )
                    opts.forEach { (v, label) ->
                        FilterChip(
                            selected = filterDue == v,
                            onClick = { vm.filterDue.value = v },
                            label = { Text(label) },
                        )
                    }
                }
                if (filterActive) {
                    Spacer(Modifier.height(6.dp))
                    AssistChip(
                        onClick = {
                            vm.filterCourseId.value = null
                            vm.filterDue.value = "ALL"
                            vm.filterSemesterId.value = null
                        },
                        label = { Text("清除筛选") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("排序", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = prefs.sortMode == SortMode.DEADLINE,
                        onClick = { vm.setSort(SortMode.DEADLINE) },
                        shape = SegmentedButtonDefaults.itemShape(0, 3),
                    ) { Text("截止优先", maxLines = 1) }
                    SegmentedButton(
                        selected = prefs.sortMode == SortMode.NEWEST,
                        onClick = { vm.setSort(SortMode.NEWEST) },
                        shape = SegmentedButtonDefaults.itemShape(1, 3),
                    ) { Text("最新记录", maxLines = 1) }
                    SegmentedButton(
                        selected = prefs.sortMode == SortMode.OLDEST,
                        onClick = { vm.setSort(SortMode.OLDEST) },
                        shape = SegmentedButtonDefaults.itemShape(2, 3),
                    ) { Text("最早记录", maxLines = 1) }
                }
            }

            // 作业列表
            if (todoItems.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (filtering) "筛选结果为空，换个条件试试"
                            else "太棒了，没有待办作业\n去记录一项新的作业吧",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(todoItems, key = { it.h.id }) { item ->
                    HomeworkCard(
                        item = item,
                        onOpen = { onOpenHomework(item.h.id) },
                        onToggleDone = {
                            vm.complete(item.h.id) { cname ->
                                scope.launch {
                                    val msg = if (cname != null) "已完成，已保留在${cname}中。" else "已完成。"
                                    val r = snackbar.showSnackbar(msg, actionLabel = "撤销", withDismissAction = true)
                                    if (r == SnackbarResult.ActionPerformed) vm.undoComplete(item.h.id)
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showMottoEdit) {
        AlertDialog(
            onDismissRequest = { showMottoEdit = false },
            title = { Text("给自己的话") },
            text = {
                Column {
                    OutlinedTextField(
                        value = mottoDraft,
                        onValueChange = { if (it.length <= 300) mottoDraft = it },
                        modifier = Modifier.fillMaxWidth().testTag("motto_field"),
                        placeholder = { Text("写一句给自己的话…") },
                        minLines = 3,
                        maxLines = 8,
                        supportingText = { Text("${mottoDraft.length}/300") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.prefs.setMotto(mottoDraft) }
                    showMottoEdit = false
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        scope.launch { app.prefs.setMotto("") }
                        showMottoEdit = false
                    }) { Text("清空") }
                    TextButton(onClick = { showMottoEdit = false }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun MottoCard(
    text: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onEdit: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            .padding(16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("“", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    "给自己的话",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑给自己的话", modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text.ifBlank { "先做好眼前这一件。" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onToggleExpand),
            )
            if (text.lines().size > 3 || text.length > 60) {
                TextButton(onClick = onToggleExpand) { Text(if (expanded) "收起" else "展开") }
            }
        }
    }
}
