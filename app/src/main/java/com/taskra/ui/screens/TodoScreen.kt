package com.taskra.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.taskra.TaskraApp
import com.taskra.data.SortMode
import com.taskra.data.Status
import com.taskra.ui.AppViewModel
import com.taskra.ui.TodoUiItem
import com.taskra.ui.components.HomeworkCard
import com.taskra.ui.components.NotebookEmpty
import com.taskra.ui.components.SwipeToTrash
import com.taskra.ui.components.TileDismiss
import com.taskra.ui.components.notebookPaper
import com.taskra.ui.components.rememberDragOrder
import com.taskra.ui.components.reorderItemModifier
import com.taskra.ui.filterTodo
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.RollingCountText
import com.taskra.ui.motion.TaskraMotion
import com.taskra.ui.motion.appleContentSize
import com.taskra.ui.motion.applePressScale
import com.taskra.util.HomeworkSort
import com.taskra.util.TimeUtils
import com.taskra.util.ordered
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 对钩描完再消散：钩 310ms，留一点余量；减少动态效果模式下短暂停顿后直接淡出。 */
private const val CHECK_TICK_MS = 340L
private const val CHECK_TICK_REDUCED_MS = 90L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TodoScreen(
    app: TaskraApp,
    vm: AppViewModel,
    onOpenHomework: (String) -> Unit,
    onNewHomework: (courseId: String?) -> Unit,
    onOpenDrafts: () -> Unit,
) {
    val prefs by vm.prefs.collectAsState()
    val todoAll by vm.todoAll.collectAsState()
    val courses by vm.coursesAll.collectAsState()
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
                val label = if (first.isBlank() && imgs > 0) "图片作业" else if (first.isBlank() && bl.any { it.kind == "AUDIO" }) "录音作业" else first
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
    val sorted = remember(filtered, prefs.sortMode, prefs.listOrders) {
        if (prefs.sortMode == SortMode.MANUAL) ordered(filtered, prefs.listOrders["todo"].orEmpty()) { it.id } else HomeworkSort.sort(filtered, prefs.sortMode)
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
    var mottoDraft by remember { mutableStateOf(TextFieldValue(prefs.mottoText, TextRange(prefs.mottoText.length))) }
    var mottoExpanded by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var draftQuery by remember { mutableStateOf("") }
    var draftCourse by remember { mutableStateOf<String?>(null) }
    var draftDue by remember { mutableStateOf("ALL") }
    var draftSort by remember { mutableStateOf(prefs.sortMode) }
    var swipeReset by remember { mutableStateOf(0) }
    // 完成动效分两段：checking 正在画对钩，outgoing 正在纸片消散。
    val checking = remember { mutableStateMapOf<String, Pair<TodoUiItem, Int>>() }
    val outgoing = remember { mutableStateMapOf<String, Pair<TodoUiItem, Int>>() }
    val animationFinished = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(todoAll, animationFinished.toMap()) {
        outgoing.keys.toList().forEach { id ->
            if (animationFinished[id] == true && todoAll.none { it.id == id }) {
                outgoing.remove(id)
                animationFinished.remove(id)
            }
        }
    }
    val visibleItems = todoItems
        .filterNot { checking.containsKey(it.h.id) || outgoing.containsKey(it.h.id) }
        .toMutableList()
        .apply {
            (checking.values + outgoing.values).sortedBy { it.second }
                .forEach { (item, index) -> add(index.coerceIn(0, size), item) }
        }

    val reduced = LocalReduceMotion.current

    /** 完成动效：先画对钩，再整张卡片消散。persist=false 用于“从作业页标记完成后返回”。 */
    fun playCompletion(item: TodoUiItem, index: Int, persist: Boolean) {
        val id = item.h.id
        if (checking.containsKey(id) || outgoing.containsKey(id)) return
        checking[id] = item to index
        if (persist) {
            vm.complete(id) {
                checking.remove(id)
                scope.launch { snackbar.showSnackbar("未能保存完成状态，请重试") }
            }
        }
        scope.launch {
            delay(if (reduced) CHECK_TICK_REDUCED_MS else CHECK_TICK_MS)
            val entry = checking.remove(id) ?: return@launch
            outgoing[id] = entry
        }
    }

    // 在作业页点了“完成”并返回：用离开前的卡片快照补播同一段动效。
    LaunchedEffect(Unit) {
        val pending = vm.pendingCheckId ?: return@LaunchedEffect
        val snapshot = vm.lastOpenedItem?.takeIf { it.h.id == pending }
        val index = vm.lastOpenedIndex
        vm.pendingCheckId = null
        vm.lastOpenedItem = null
        if (snapshot != null) {
            delay(90)
            playCompletion(snapshot, index, persist = false)
        }
    }

    val listState = rememberLazyListState()
    val drag = rememberDragOrder(listState, visibleItems.map { it.h.id }) { ids, rollback ->
        vm.saveOrder("todo", ids) { rollback(); scope.launch { snackbar.showSnackbar("排序保存失败，请重试") } }
    }
    val displayedItems = ordered(visibleItems, drag.ids) { it.h.id }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            com.taskra.ui.components.NotebookFab(
                "新建作业",
                onClick = { onNewHomework(filterCourse?.takeIf { it != "UNCAT" }) },
            )
        },
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .notebookPaper()
                .padding(pad)
                .testTag("todo_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 24.dp, end = 24.dp, top = 20.dp, bottom = 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 标题与搜索入口
            item(key = "todo_header") {
                val filterTint by animateColorAsState(
                    targetValue = if (filterActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    animationSpec = TaskraMotion.colorSpec(reduced, TaskraMotion.QuickMs),
                    label = "filterIconTint",
                )
                val searchTint by animateColorAsState(
                    targetValue = if (query.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    animationSpec = TaskraMotion.colorSpec(reduced, TaskraMotion.QuickMs),
                    label = "searchIconTint",
                )
                val draftsInteraction = remember { MutableInteractionSource() }
                val filterInteraction = remember { MutableInteractionSource() }
                val searchInteraction = remember { MutableInteractionSource() }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    com.taskra.ui.components.NotebookHeading("待办", Modifier.weight(1f))
                    IconButton(
                        onClick = onOpenDrafts,
                        interactionSource = draftsInteraction,
                        modifier = Modifier.applePressScale(draftsInteraction, 0.90f),
                    ) {
                        BadgedBox(badge = {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = drafts.isNotEmpty(),
                                enter = TaskraMotion.popEnter(reduced),
                                exit = TaskraMotion.popExit(reduced),
                            ) {
                                Badge {
                                    RollingCountText(
                                        count = drafts.size,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }) {
                            Icon(
                                Icons.Outlined.Drafts,
                                "草稿箱",
                                tint = if (drafts.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    IconButton(
                        onClick = { draftCourse = filterCourse; draftDue = filterDue; draftSort = prefs.sortMode; showFilters = true },
                        interactionSource = filterInteraction,
                        modifier = Modifier.applePressScale(filterInteraction, 0.90f),
                    ) {
                        Icon(Icons.Outlined.Tune, "筛选与排序", tint = filterTint)
                    }
                    IconButton(
                        onClick = { draftQuery = query; showSearch = true },
                        interactionSource = searchInteraction,
                        modifier = Modifier.applePressScale(searchInteraction, 0.90f),
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = "搜索", tint = searchTint)
                    }
                }
            }

            // 给自己的话
            if (prefs.mottoVisible) {
                item(key = "todo_motto") {
                    MottoCard(
                        text = prefs.mottoText,
                        expanded = mottoExpanded,
                        onToggleExpand = { mottoExpanded = !mottoExpanded },
                        onEdit = {
                            mottoDraft = TextFieldValue(prefs.mottoText, TextRange(prefs.mottoText.length))
                            showMottoEdit = true
                        },
                        modifier = Modifier.animateItem(
                            fadeInSpec = TaskraMotion.listFadeSpec(reduced),
                            fadeOutSpec = TaskraMotion.listFadeSpec(reduced),
                            placementSpec = TaskraMotion.listPlacementSpec(reduced),
                        ),
                    )
                }
            }

            // 待办数量和截止概览（数字平滑滚动，不跳字）
            item(key = "todo_summary") {
                val summaryColor = MaterialTheme.colorScheme.onSurfaceVariant
                val summaryStyle = MaterialTheme.typography.bodyMedium
                AnimatedContent(
                    targetState = filtering,
                    transitionSpec = { TaskraMotion.crossfadeSpec(reduced) },
                    label = "todoSummaryMode",
                    modifier = Modifier.animateItem(
                        fadeInSpec = TaskraMotion.listFadeSpec(reduced),
                        fadeOutSpec = TaskraMotion.listFadeSpec(reduced),
                        placementSpec = TaskraMotion.listPlacementSpec(reduced),
                    ),
                ) { isFiltering ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isFiltering) {
                            RollingCountText(todoItems.size, style = summaryStyle, color = summaryColor)
                            Text(" 项结果", style = summaryStyle, color = summaryColor)
                        } else {
                            RollingCountText(todoAll.size, style = summaryStyle, color = summaryColor)
                            Text(" 项待办", style = summaryStyle, color = summaryColor)
                            AnimatedVisibility(
                                visible = overdueCount > 0,
                                enter = TaskraMotion.popEnter(reduced),
                                exit = TaskraMotion.popExit(reduced),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(" · ", style = summaryStyle, color = summaryColor)
                                    RollingCountText(overdueCount, style = summaryStyle, color = MaterialTheme.colorScheme.error)
                                    Text(" 项逾期", style = summaryStyle, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            // 当前生效的搜索与筛选胶囊：仅在筛选时出现，轻点即可单项关闭，无需再钻弹窗
            if (filtering) {
                item(key = "todo_active_filters") {
                    val activeCount = (if (query.isNotBlank()) 1 else 0) +
                        (if (filterCourse != null) 1 else 0) +
                        (if (filterDue != "ALL") 1 else 0)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("todo_active_filters")
                            .animateItem(
                                fadeInSpec = TaskraMotion.listFadeSpec(reduced),
                                fadeOutSpec = TaskraMotion.listFadeSpec(reduced),
                                placementSpec = TaskraMotion.listPlacementSpec(reduced),
                            ),
                    ) {
                        if (query.isNotBlank()) {
                            InputChip(
                                selected = true,
                                onClick = { vm.query.value = "" },
                                label = { Text("搜索：${query.trim()}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = { Icon(Icons.Outlined.Close, "清除搜索词", Modifier.size(16.dp)) },
                            )
                        }
                        if (filterCourse != null) {
                            val label = if (filterCourse == "UNCAT") "未分类" else courseMap[filterCourse]?.name ?: "课程"
                            InputChip(
                                selected = true,
                                onClick = { vm.filterCourseId.value = null },
                                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = { Icon(Icons.Outlined.Close, "清除课程筛选", Modifier.size(16.dp)) },
                            )
                        }
                        if (filterDue != "ALL") {
                            val dueLabel = when (filterDue) {
                                "OVERDUE" -> "已逾期"
                                "TODAY" -> "今天截止"
                                "WEEK" -> "未来七天"
                                "NONE" -> "无截止时间"
                                else -> filterDue
                            }
                            InputChip(
                                selected = true,
                                onClick = { vm.filterDue.value = "ALL" },
                                label = { Text(dueLabel) },
                                trailingIcon = { Icon(Icons.Outlined.Close, "清除截止筛选", Modifier.size(16.dp)) },
                            )
                        }
                        if (activeCount >= 2) {
                            AssistChip(
                                onClick = {
                                    vm.query.value = ""
                                    vm.filterCourseId.value = null
                                    vm.filterDue.value = "ALL"
                                    vm.filterSemesterId.value = null
                                },
                                label = { Text("全部清除") },
                            )
                        }
                    }
                }
            }

            // 作业列表
            if (visibleItems.isEmpty()) {
                item(key = "todo_empty") {
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
                        NotebookEmpty(title = if (filtering) "暂无结果" else "暂无待办")
                        if (filtering) {
                            OutlinedButton(
                                onClick = {
                                    vm.query.value = ""
                                    vm.filterCourseId.value = null
                                    vm.filterDue.value = "ALL"
                                    vm.filterSemesterId.value = null
                                },
                            ) { Text("清除筛选") }
                        }
                    }
                }
            } else {
                items(displayedItems, key = { it.h.id }) { item ->
                    SwipeToTrash(
                        resetKey = swipeReset,
                        enabled = !outgoing.containsKey(item.h.id) && !checking.containsKey(item.h.id) && drag.dragging == null,
                        modifier = reorderItemModifier(drag, item.h.id, outgoing.isEmpty() && checking.isEmpty()).testTag("swipe_homework:${item.h.id}"),
                        onDelete = {
                            vm.trashHomework(
                                id = item.h.id,
                                onSuccess = {
                                    scope.launch {
                                        snackbar.currentSnackbarData?.dismiss()
                                        val res = snackbar.showSnackbar(
                                            message = "已移入回收站",
                                            actionLabel = "撤销",
                                            duration = SnackbarDuration.Short,
                                        )
                                        if (res == SnackbarResult.ActionPerformed) {
                                            vm.restoreHomework(item.h.id) {
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
                    TileDismiss(
                        removing = outgoing.containsKey(item.h.id),
                        onFinished = { animationFinished[item.h.id] = true },
                    ) {
                        HomeworkCard(
                            item = item,
                            done = checking.containsKey(item.h.id),
                            onOpen = {
                                if (!outgoing.containsKey(item.h.id) && !checking.containsKey(item.h.id)) {
                                    vm.lastOpenedItem = item
                                    vm.lastOpenedIndex = visibleItems.indexOf(item).coerceAtLeast(0)
                                    onOpenHomework(item.h.id)
                                }
                            },
                            onToggleDone = { playCompletion(item, visibleItems.indexOf(item).coerceAtLeast(0), persist = true) },
                        )
                    }
                    }
                }
            }
        }
    }

    if (showSearch) {
        val focus = remember { FocusRequester() }
        var focusRequested by remember { mutableStateOf(false) }
        fun applySearch() { vm.query.value = draftQuery; showSearch = false }
        AlertDialog(
            modifier = Modifier.testTag("search_dialog"),
            onDismissRequest = { showSearch = false },
            title = { Text("搜索") },
            text = {
                OutlinedTextField(
                    value = draftQuery, onValueChange = { draftQuery = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("search_field")
                        .onGloballyPositioned {
                            if (!focusRequested) { focusRequested = true; focus.requestFocus() }
                        },
                    placeholder = { Text("搜索作业") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { applySearch() }),
                    trailingIcon = {
                        AnimatedVisibility(
                            visible = draftQuery.isNotEmpty(),
                            enter = TaskraMotion.popEnter(reduced),
                            exit = TaskraMotion.popExit(reduced),
                        ) {
                            IconButton(onClick = { draftQuery = "" }) {
                                Icon(Icons.Outlined.Close, "清除搜索")
                            }
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { applySearch() }) {
                    Text(if (query.isNotBlank() && draftQuery.isBlank()) "清除搜索" else "搜索")
                }
            },
            dismissButton = { TextButton(onClick = { showSearch = false }) { Text("取消") } },
        )
    }
    if (showFilters) {
        AlertDialog(
            modifier = Modifier.testTag("filter_dialog"),
            onDismissRequest = { showFilters = false },
            title = { Text("筛选与排序") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()).appleContentSize()) {
                // 课程筛选
                Text("课程", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = draftCourse == null,
                        onClick = { draftCourse = null },
                        label = { Text("全部课程") },
                    )
                    FilterChip(
                        selected = draftCourse == "UNCAT",
                        onClick = { draftCourse = "UNCAT" },
                        label = { Text("未分类") },
                    )
                    courses.forEach { c ->
                        FilterChip(
                            selected = draftCourse == c.id,
                            onClick = { draftCourse = c.id },
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
                            selected = draftDue == v,
                            onClick = { draftDue = v },
                            label = { Text(label) },
                        )
                    }
                }
                AnimatedVisibility(
                    visible = draftCourse != null || draftDue != "ALL",
                    enter = TaskraMotion.expandEnter(reduced),
                    exit = TaskraMotion.collapseExit(reduced),
                ) {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        AssistChip(
                            onClick = {
                                draftCourse = null
                                draftDue = "ALL"
                            },
                            label = { Text("清除筛选") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("排序", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(SortMode.MANUAL to "手动排序", SortMode.DEADLINE to "截止优先", SortMode.NEWEST to "最新记录", SortMode.OLDEST to "最早记录").forEach { (mode, label) ->
                        FilterChip(selected = draftSort == mode, onClick = { draftSort = mode }, label = { Text(label) })
                    }
                }
            } },
            confirmButton = { TextButton(onClick = {
                vm.filterCourseId.value = draftCourse
                vm.filterDue.value = draftDue
                vm.filterSemesterId.value = null
                vm.setSort(draftSort)
                showFilters = false
            }) { Text("应用") } },
            dismissButton = { TextButton(onClick = { showFilters = false }) { Text("取消") } },
        )
    }

    if (showMottoEdit) {
        val mottoFocus = remember { FocusRequester() }
        var mottoFocusRequested by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showMottoEdit = false },
            title = { Text("给自己的话") },
            text = {
                Column {
                    OutlinedTextField(
                        value = mottoDraft,
                        onValueChange = { if (it.text.length <= 300) mottoDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(mottoFocus)
                            .onGloballyPositioned {
                                if (!mottoFocusRequested) {
                                    mottoFocusRequested = true
                                    mottoFocus.requestFocus()
                                }
                            }
                            .testTag("motto_field"),
                        placeholder = { Text("写一句给自己的话…") },
                        minLines = 3,
                        maxLines = 8,
                        supportingText = { Text("${mottoDraft.text.length}/300") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.prefs.setMotto(mottoDraft.text) }
                    showMottoEdit = false
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (mottoDraft.text.isNotEmpty()) {
                        TextButton(onClick = {
                            scope.launch { app.prefs.setMotto("") }
                            showMottoEdit = false
                        }) { Text("清空") }
                    }
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
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReduceMotion.current
    val marginInk = MaterialTheme.colorScheme.primary
    var clipped by remember(text) { mutableStateOf(false) }
    val arrowRotationState = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduced) tween(0) else TaskraMotion.spatialSpring(),
        label = "mottoArrow",
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(5.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .appleContentSize()
                .drawBehind { drawLine(marginInk, Offset.Zero, Offset(0f, size.height), 3.5.dp.toPx()) }
                .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "“",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "给自己的话",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val editInteraction = remember { MutableInteractionSource() }
                IconButton(
                    onClick = onEdit,
                    interactionSource = editInteraction,
                    modifier = Modifier.size(36.dp).applePressScale(editInteraction, 0.88f),
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "编辑给自己的话",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text.ifBlank { "先做好眼前这一件。" },
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp),
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) clipped = it.hasVisualOverflow },
                modifier = Modifier.clickable(onClick = onToggleExpand),
            )
            AnimatedVisibility(
                visible = clipped || expanded,
                enter = TaskraMotion.expandEnter(reduced),
                exit = TaskraMotion.collapseExit(reduced),
            ) {
                TextButton(onClick = onToggleExpand) {
                    Text(if (expanded) "收起" else "展开")
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = arrowRotationState.value },
                    )
                }
            }
        }
    }
}
