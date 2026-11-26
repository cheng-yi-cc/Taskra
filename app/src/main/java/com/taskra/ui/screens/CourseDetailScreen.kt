package com.taskra.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.taskra.TaskraApp
import com.taskra.data.Status
import com.taskra.ui.AppViewModel
import com.taskra.ui.TodoUiItem
import com.taskra.ui.components.HomeworkCard
import com.taskra.ui.components.NotebookEmpty
import com.taskra.ui.components.PaperBackButton
import com.taskra.ui.components.PaperTopBar as TopAppBar
import com.taskra.ui.components.SwipeToTrash
import com.taskra.ui.components.notebookPaper
import com.taskra.ui.components.rememberDragOrder
import com.taskra.ui.components.reorderItemModifier
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.RollingCountText
import com.taskra.ui.motion.TaskraMotion
import com.taskra.ui.motion.appleContentSize
import com.taskra.ui.motion.errorShake
import com.taskra.util.TimeUtils
import com.taskra.util.ordered
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var sortMode by remember { mutableStateOf("MANUAL") } // MANUAL / OLDEST / NEWEST
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf("ALL") } // 默认全部作业
    var showFilter by remember { mutableStateOf(false) }
    var pendingTab by remember { mutableStateOf("ALL") }
    var pendingSort by remember { mutableStateOf("MANUAL") }
    var swipeReset by remember { mutableIntStateOf(0) }
    val reduced = LocalReduceMotion.current
    var showRename by remember { mutableStateOf(false) }
    var showCourseActions by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(TextFieldValue("")) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var renameErrorShake by remember { mutableIntStateOf(0) }

    var courseName by remember { mutableStateOf("") }
    var courseObj by remember { mutableStateOf<com.taskra.data.Course?>(null) }
    LaunchedEffect(courseId) {
        if (courseId != null) {
            val c = app.repo.getCourse(courseId)
            courseObj = c
            courseName = c?.name ?: ""
            renameText = TextFieldValue(courseName, TextRange(courseName.length))
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
    val sorted = remember(filtered, sortMode, prefs.listOrders) {
        when {
            sortMode == "MANUAL" && prefs.listOrders.containsKey(orderKey) ->
                ordered(filtered, prefs.listOrders[orderKey].orEmpty()) { it.id }
            sortMode == "NEWEST" -> filtered.sortedByDescending { it.createdAt }
            else -> filtered.sortedWith(compareBy({ it.createdAt }, { it.id }))
        }
    }

    val total = base.size
    val undone = base.count { it.status == Status.TODO }
    val done = total - undone
    val semId = courseObj?.semesterId ?: semesterId ?: ""

    val listState = rememberLazyListState()
    val scrolled by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8 }
    }
    val filterTint by animateColorAsState(
        targetValue = if (tab != "ALL") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        animationSpec = TaskraMotion.colorSpec(reduced, TaskraMotion.QuickMs),
        label = "courseFilterTint",
    )
    val drag = rememberDragOrder(listState, sorted.map { it.id }) { ids, rollback ->
        sortMode = "MANUAL"
        vm.saveOrder(orderKey, ids) { rollback(); scope.launch { snackbar.showSnackbar("排序保存失败，请重试") } }
    }
    val displayedItems = ordered(sorted, drag.ids) { it.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (courseId == null) "未分类" else courseName.ifBlank { "课程" }) },
                scrolled = scrolled,
                navigationIcon = { PaperBackButton(onClick = onBack) },
                actions = {
                    IconButton(onClick = { pendingTab = tab; pendingSort = sortMode; showFilter = true }) {
                        Icon(Icons.Outlined.Tune, "筛选课程作业", tint = filterTint)
                    }
                    if (courseId != null) Box {
                        IconButton(onClick = { showCourseActions = true }) { Icon(Icons.Outlined.MoreHoriz, "课程操作") }
                        DropdownMenu(expanded = showCourseActions, onDismissRequest = { showCourseActions = false }) {
                            DropdownMenuItem(text = { Text("重命名") }, onClick = {
                                showCourseActions = false
                                renameText = TextFieldValue(courseName, TextRange(courseName.length))
                                renameError = null
                                showRename = true
                            })
                            DropdownMenuItem(
                                text = {
                                    Text(if (!courseObj?.templateJson.isNullOrBlank()) "课程模板（已设置）" else "课程模板")
                                },
                                onClick = {
                                    showCourseActions = false
                                    onEditTemplate(courseId)
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            com.taskra.ui.components.NotebookFab("在此课程新建作业", onClick = { onNewHomework(semId, courseId) })
        },
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().notebookPaper().padding(pad)
                .testTag("course_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp, 16.dp, 24.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "course_summary") {
                val style = MaterialTheme.typography.bodyMedium
                val color = MaterialTheme.colorScheme.onSurfaceVariant
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        total == 0 -> Text("暂无作业", style = style, color = color)
                        done == 0 -> {
                            RollingCountText(undone, style = style, color = color)
                            Text(" 项待办", style = style, color = color)
                        }
                        undone == 0 -> {
                            RollingCountText(done, style = style, color = color)
                            Text(" 项已完成", style = style, color = color)
                        }
                        else -> {
                            RollingCountText(undone, style = style, color = color)
                            Text(" 项待办 · ", style = style, color = color)
                            RollingCountText(done, style = style, color = color)
                            Text(" 项完成", style = style, color = color)
                        }
                    }
                }
            }
            if (tab != "ALL") {
                item(key = "course_active_filter") {
                    InputChip(
                        selected = true,
                        onClick = { tab = "ALL" },
                        label = { Text(if (tab == "TODO") "仅看未完成" else "仅看已完成") },
                        trailingIcon = { Icon(Icons.Outlined.Close, "清除状态筛选", Modifier.size(16.dp)) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = TaskraMotion.listFadeSpec(reduced),
                            fadeOutSpec = TaskraMotion.listFadeSpec(reduced),
                            placementSpec = TaskraMotion.listPlacementSpec(reduced),
                        ),
                    )
                }
            }
            if (sorted.isEmpty()) {
                item(key = "course_empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(
                                fadeInSpec = TaskraMotion.listFadeSpec(reduced),
                                fadeOutSpec = TaskraMotion.listFadeSpec(reduced),
                                placementSpec = TaskraMotion.listPlacementSpec(reduced),
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = { TaskraMotion.crossfadeSpec(reduced) },
                            label = "courseEmptyTab",
                        ) { curTab ->
                            NotebookEmpty(
                                when (curTab) {
                                    "DONE" -> "暂无已完成作业"
                                    "TODO" -> "暂无未完成作业"
                                    else -> "暂无作业"
                                }
                            )
                        }
                        if (tab != "ALL") {
                            OutlinedButton(onClick = { tab = "ALL" }) { Text("显示全部作业") }
                        }
                    }
                }
            } else {
                items(displayedItems, key = { it.id }) { h ->
                    val overdue = TimeUtils.isOverdue(h.deadlineType, h.deadlineDate, h.deadlineTime, h.deadlineZoneId)
                    SwipeToTrash(
                        resetKey = swipeReset,
                        enabled = drag.dragging == null,
                        modifier = reorderItemModifier(drag, h.id).testTag("drag_homework:${h.id}"),
                        onDelete = {
                            vm.trashHomework(
                                id = h.id,
                                onSuccess = {
                                    scope.launch {
                                        snackbar.currentSnackbarData?.dismiss()
                                        val res = snackbar.showSnackbar(
                                            message = "已移入回收站",
                                            actionLabel = "撤销",
                                            duration = SnackbarDuration.Short,
                                        )
                                        if (res == SnackbarResult.ActionPerformed) {
                                            vm.restoreHomework(h.id) {
                                                scope.launch { snackbar.showSnackbar("恢复失败，请重试") }
                                            }
                                            swipeReset++
                                        }
                                    }
                                },
                                onFailure = { swipeReset++; scope.launch { snackbar.showSnackbar("删除失败，请重试") } },
                            )
                        },
                    ) {
                        HomeworkCard(
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
                        )
                    }
                }
            }
        }
    }

    if (showFilter) {
        AlertDialog(
            onDismissRequest = { showFilter = false },
            title = { Text("筛选与排序") },
            text = {
                Column(Modifier.appleContentSize()) {
                    Text("作业状态", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("ALL" to "全部", "TODO" to "未完成", "DONE" to "已完成").forEach { (value, label) ->
                            FilterChip(selected = pendingTab == value, onClick = { pendingTab = value }, label = { Text(label) })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("排序", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("MANUAL" to "手动排序", "OLDEST" to "从早到晚", "NEWEST" to "最新优先").forEach { (mode, label) ->
                            FilterChip(selected = pendingSort == mode, onClick = { pendingSort = mode }, label = { Text(label) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    tab = pendingTab
                    sortMode = pendingSort
                    showFilter = false
                }) { Text("应用") }
            },
            dismissButton = { TextButton(onClick = { showFilter = false }) { Text("取消") } },
        )
    }

    if (showRename && courseObj != null) {
        val renameFocus = remember { FocusRequester() }
        var focusRequested by remember { mutableStateOf(false) }
        fun submitRename() {
            scope.launch {
                val name = renameText.text.trim()
                if (name.isEmpty()) {
                    renameError = "课程名不能为空"; renameErrorShake++; return@launch
                }
                val dup = app.repo.db.courseDao().listAll().firstOrNull { it.name == name && it.id != courseObj!!.id }
                if (dup != null) {
                    renameError = "已存在同名课程"; renameErrorShake++; return@launch
                }
                val updated = courseObj!!.copy(name = name)
                app.repo.saveCourse(updated)
                courseObj = updated
                courseName = name
                showRename = false
                snackbar.showSnackbar("已重命名")
            }
        }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名课程") },
            text = {
                Column(Modifier.appleContentSize()) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it; renameError = null },
                        singleLine = true,
                        isError = renameError != null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitRename() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(renameFocus)
                            .onGloballyPositioned {
                                if (!focusRequested) {
                                    focusRequested = true
                                    renameFocus.requestFocus()
                                }
                            }
                            .errorShake(renameErrorShake),
                    )
                    AnimatedVisibility(
                        visible = renameError != null,
                        enter = TaskraMotion.expandEnter(reduced),
                        exit = TaskraMotion.collapseExit(reduced),
                    ) {
                        Text(
                            renameError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { submitRename() }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } },
        )
    }
}
