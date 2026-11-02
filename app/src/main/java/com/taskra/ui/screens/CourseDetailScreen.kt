package com.taskra.ui.screens

import com.taskra.ui.components.notebookPaper

import androidx.compose.foundation.lazy.rememberLazyListState
import com.taskra.ui.components.rememberDragOrder
import com.taskra.ui.components.reorderItemModifier
import com.taskra.util.ordered


import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.taskra.ui.components.PaperTopBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.taskra.TaskraApp
import com.taskra.data.ReviewSession
import com.taskra.data.SessionStatus
import com.taskra.data.Status
import com.taskra.ui.AppViewModel
import com.taskra.ui.TodoUiItem
import com.taskra.ui.components.HomeworkCard
import com.taskra.util.TimeUtils
import com.taskra.util.postOnMain
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(
    app: TaskraApp,
    vm: AppViewModel,
    courseId: String?, // null = 未分类（需 semesterId）
    semesterId: String?,
    onBack: () -> Unit,
    onEditTemplate: (String) -> Unit = {},
    onOpenHomework: (String) -> Unit,
    onNewHomework: (semId: String, cId: String?) -> Unit,
) {
    val prefs by vm.prefs.collectAsState()
    val orderKey = "course:${courseId ?: "UNCAT"}"
    var manual by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf("ALL") } // 默认全部作业
    var sortNewest by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var pendingTab by remember { mutableStateOf("ALL") }
    var pendingNewest by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showCourseActions by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var showEndOld by remember { mutableStateOf<ReviewSession?>(null) }

    val course by remember(courseId) {
        mutableStateOf<String?>(null)
    }
    var courseName by remember { mutableStateOf("") }
    var courseObj by remember { mutableStateOf<com.taskra.data.Course?>(null) }
    LaunchedEffect(courseId) {
        if (courseId != null) {
            val c = app.repo.getCourse(courseId)
            courseObj = c
            courseName = c?.name ?: ""
            renameText = c?.name ?: ""
        }
    }
    val list by remember(courseId, semesterId) {
        if (courseId != null) app.repo.observeByCourse(courseId)
        else app.repo.db.homeworkDao().observeUncatAll()
    }.collectAsState(initial = emptyList())

    val base = list
    val filtered = remember(base, tab) {
        when (tab) {
            "DONE" -> base.filter { it.status == Status.DONE }
            "TODO" -> base.filter { it.status == Status.TODO }
            else -> base
        }
    }
    val sorted = remember(filtered, sortNewest, prefs.listOrders, manual) {
        if (manual && prefs.listOrders.containsKey(orderKey)) ordered(filtered, prefs.listOrders[orderKey].orEmpty()) { it.id }
        else if (sortNewest) filtered.sortedByDescending { it.createdAt }
        else filtered.sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    val total = base.size
    val undone = base.count { it.status == Status.TODO }
    val done = total - undone
    val semId = courseObj?.semesterId ?: semesterId ?: ""

    val listState = rememberLazyListState()
    val drag = rememberDragOrder(listState, sorted.map { it.id }) { ids, rollback ->
        manual = true
        vm.saveOrder(orderKey, ids) { rollback(); scope.launch { snackbar.showSnackbar("排序保存失败，请重试") } }
    }
    val displayedItems = ordered(sorted, drag.ids) { it.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (courseId == null) "未分类" else courseName.ifBlank { "课程" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { pendingTab = tab; pendingNewest = sortNewest; showFilter = true }) {
                        Icon(Icons.Outlined.Tune, "筛选课程作业", tint = if (tab != "ALL") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    if (courseId != null) Box {
                        IconButton(onClick = { showCourseActions = true }) { Icon(Icons.Outlined.MoreHoriz, "课程操作") }
                        DropdownMenu(expanded = showCourseActions, onDismissRequest = { showCourseActions = false }) {
                            DropdownMenuItem(text = { Text("重命名") }, onClick = { showCourseActions = false; showRename = true })
                            DropdownMenuItem(text = { Text("课程模板") }, onClick = {
                                showCourseActions = false
                                onEditTemplate(courseId)
                            })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNewHomework(semId, courseId) }) {
                Icon(Icons.Outlined.Add, contentDescription = "在此课程新建作业")
            }
        },
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().notebookPaper().padding(pad)
                .testTag("course_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp, 16.dp, 24.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "$undone 项待办 · $done 项完成",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sorted.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.surface).padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (tab) {
                                "DONE" -> "暂无已完成作业"
                                "TODO" -> "暂无未完成作业"
                                else -> "暂无作业"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(displayedItems, key = { it.id }) { h ->
                    val c = h.courseId?.let { null } // 课程页内不再重复取
                    val overdue = TimeUtils.isOverdue(h.deadlineType, h.deadlineDate, h.deadlineTime, h.deadlineZoneId)
                    Box(reorderItemModifier(drag, h.id).testTag("drag_homework:${h.id}")) { HomeworkCard(
                        item = TodoUiItem(
                            h, courseName.ifBlank { null }, courseObj?.colorIndex ?: 0,
                            overdue,
                            TimeUtils.formatDeadline(h.deadlineType, h.deadlineDate, h.deadlineTime, h.deadlineZoneId),
                            "", 0,
                        ),
                        done = h.status == Status.DONE,
                        onOpen = { onOpenHomework(h.id) },
                        onToggleDone = {
                            scope.launch {
                                if (h.status == Status.DONE) app.repo.markTodo(h.id)
                                else app.repo.markDone(h.id)
                            }
                        },
                    ) }
                }
            }
        }
    }

    if (showFilter) {
        AlertDialog(
            onDismissRequest = { showFilter = false },
            title = { Text("筛选与排序") },
            text = { Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL" to "全部", "TODO" to "未完成", "DONE" to "已完成").forEach { (value, label) ->
                        FilterChip(selected = pendingTab == value, onClick = { pendingTab = value }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !pendingNewest, onClick = { pendingNewest = false }, label = { Text("从早到晚") })
                    FilterChip(selected = pendingNewest, onClick = { pendingNewest = true }, label = { Text("最新优先") })
                }
            } },
            confirmButton = { TextButton(onClick = { tab = pendingTab; sortNewest = pendingNewest; manual = false; showFilter = false }) { Text("应用") } },
            dismissButton = { TextButton(onClick = { showFilter = false }) { Text("取消") } },
        )
    }

    if (showRename && courseObj != null) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名课程") },
            text = {
                Column {
                    OutlinedTextField(value = renameText, onValueChange = { renameText = it; renameError = null }, singleLine = true)
                    if (renameError != null) Text(renameError!!, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val name = renameText.trim()
                        if (name.isEmpty()) {
                            renameError = "课程名不能为空"; return@launch
                        }
                        val dup = app.repo.db.courseDao().listAll().firstOrNull { it.name == name && it.id != courseObj!!.id }
                        if (dup != null) {
                            renameError = "已存在同名课程"; return@launch
                        }
                        app.repo.saveCourse(courseObj!!.copy(name = name))
                        courseName = name
                        showRename = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } },
        )
    }
}
