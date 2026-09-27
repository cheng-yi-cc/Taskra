package com.taskra.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.taskra.TaskraApp
import com.taskra.data.ReviewSession
import com.taskra.data.ReviewResult
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
    onOpenHomework: (String) -> Unit,
    onNewHomework: (semId: String, cId: String?) -> Unit,
    onOpenReview: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf("DONE") } // DONE/ALL/TODO，默认已完成
    var sortNewest by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
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
        else if (semesterId != null) app.repo.db.homeworkDao().observeUncat(semesterId)
        else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val base = list
    val filtered = remember(base, tab) {
        when (tab) {
            "DONE" -> base.filter { it.status == Status.DONE }
            "TODO" -> base.filter { it.status == Status.TODO }
            else -> base
        }
    }
    val sorted = remember(filtered, sortNewest) {
        if (sortNewest) filtered.sortedByDescending { it.createdAt }
        else filtered.sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    val total = base.size
    val undone = base.count { it.status == Status.TODO }
    val done = total - undone
    val semId = courseObj?.semesterId ?: semesterId ?: ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (courseId == null) "未分类" else courseName.ifBlank { "课程" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
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
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(pad)
                .testTag("course_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "共 $total · 未完成 $undone · 已完成 $done",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tab == "DONE", onClick = { tab = "DONE" }, label = { Text("已完成") })
                    FilterChip(selected = tab == "ALL", onClick = { tab = "ALL" }, label = { Text("全部") })
                    FilterChip(selected = tab == "TODO", onClick = { tab = "TODO" }, label = { Text("未完成") })
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = !sortNewest,
                        onClick = { sortNewest = false },
                        label = { Text("从早到晚") },
                    )
                    FilterChip(
                        selected = sortNewest,
                        onClick = { sortNewest = true },
                        label = { Text("最新优先") },
                    )
                    if (courseId != null) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            "重命名",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showRename = true }.padding(8.dp),
                        )
                        Text(
                            if (courseObj?.isActive == false) "启用" else "停用",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    courseObj?.let {
                                        // 有关联记录时仅停用，不删除；删除规则见仓库 courseDeleteBlockReason
                                        app.repo.saveCourse(it.copy(isActive = !it.isActive))
                                        courseObj = app.repo.getCourse(it.id)
                                    }
                                }
                            }.padding(8.dp),
                        )
                    }
                }
            }
            if (courseId != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                val ongoing = app.repo.ongoingSession(courseId)
                                if (ongoing != null) {
                                    postOnMain { onOpenReview(ongoing.id) }
                                } else {
                                    // 新一轮：纳入未删除已完成作业，按记录时间从早到晚
                                    val doneList = app.repo.db.homeworkDao()
                                        .listByCourse(courseId)
                                        .filter { it.status == Status.DONE }
                                        .sortedWith(compareBy({ it.createdAt }, { it.id }))
                                    if (doneList.isEmpty()) {
                                        snackbar.showSnackbar("该课程暂无已完成作业，先去完成作业吧")
                                        return@launch
                                    }
                                    val ids = doneList.map { it.id }
                                    val s = ReviewSession(
                                        id = UUID.randomUUID().toString(),
                                        courseId = courseId,
                                        status = SessionStatus.ONGOING,
                                        homeworkIdsJson = Json.encodeToString(ids),
                                        currentIndex = 0,
                                    )
                                    app.repo.saveSession(s)
                                    postOnMain { onOpenReview(s.id) }
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("重新练习本课程作业", style = MaterialTheme.typography.titleMedium)
                                Text("默认只看题目，解答折叠；进度单独保存", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            if (sorted.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
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
                items(sorted, key = { it.id }) { h ->
                    val c = h.courseId?.let { null } // 课程页内不再重复取
                    val overdue = TimeUtils.isOverdue(h.deadlineType, h.deadlineDate, h.deadlineTime, h.deadlineZoneId)
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
                        val dup = app.repo.findDuplicateCourse(courseObj!!.semesterId, name, excludeId = courseObj!!.id)
                        if (dup != null) {
                            renameError = "该学期已存在同名课程"; return@launch
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
