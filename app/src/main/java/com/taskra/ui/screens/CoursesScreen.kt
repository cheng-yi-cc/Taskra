package com.taskra.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskra.TaskraApp
import com.taskra.data.Course
import com.taskra.data.Semester
import com.taskra.data.Status
import com.taskra.ui.AppViewModel
import com.taskra.ui.components.NotebookEmpty
import com.taskra.ui.components.SwipeToTrash
import com.taskra.ui.components.notebookPaper
import com.taskra.ui.components.rememberDragOrder
import com.taskra.ui.components.reorderItemModifier
import com.taskra.ui.theme.NotebookHand
import com.taskra.ui.theme.courseColor
import com.taskra.util.ordered
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CoursesScreen(
    app: TaskraApp,
    vm: AppViewModel,
    onOpenCourse: (String) -> Unit,
    onOpenHomework: (String) -> Unit,
    onOpenUncat: (String) -> Unit,
) {
    val semesters by vm.semesters.collectAsState()
    val courses by vm.coursesAll.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var swipeReset by remember { mutableStateOf(0) }
    var showNewCourse by remember { mutableStateOf(false) }
    var newCourseName by remember { mutableStateOf("") }
    var newCourseError by remember { mutableStateOf<String?>(null) }
    // 默认学期：当前学期偏好 → 第一个未归档 → 第一个
    val defaultSem = remember(semesters, prefs.currentSemesterId) {
        semesters.find { it.id == prefs.currentSemesterId }
            ?: semesters.firstOrNull { !it.isArchived }
            ?: semesters.firstOrNull()
    }
    var selectedSemId by remember(defaultSem?.id) { mutableStateOf(defaultSem?.id) }
    // 学期不存在时自动建一个默认学期，保证“允许直接记录”
    LaunchedEffect2(semesters.isEmpty()) {
        if (semesters.isEmpty()) {
            val id = UUID.randomUUID().toString()
            app.repo.saveSemester(Semester(id, "2026 秋季", false, 0))
            app.prefs.setCurrentSemester(id)
        }
    }

    val listState = rememberLazyListState()
    val sortedCourses = ordered(courses, prefs.listOrders["courses"].orEmpty()) { it.id }
    val drag = rememberDragOrder(listState, sortedCourses.map { it.id }) { ids, rollback ->
        vm.saveOrder("courses", ids) { rollback(); scope.launch { snackbar.showSnackbar("排序保存失败，请重试") } }
    }
    val displayedCourses = ordered(sortedCourses, drag.ids) { it.id }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            com.taskra.ui.components.NotebookFab("新建课程", onClick = { showNewCourse = true })
        },
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .notebookPaper()
                .padding(pad)
                .testTag("courses_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp, 16.dp, 24.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                com.taskra.ui.components.NotebookHeading("课程")
                Spacer(Modifier.height(4.dp))
            }
            if (displayedCourses.isEmpty()) {
                item {
                    NotebookEmpty("暂无课程")
                }
            }
            items(displayedCourses, key = { it.id }) { c ->
                SwipeToTrash(
                    enabled = drag.dragging == null,
                    modifier = reorderItemModifier(drag, c.id).testTag("swipe_course:${c.id}"),
                    resetKey = swipeReset,
                    onDelete = { vm.trashCourse(c.id) { swipeReset++; scope.launch { snackbar.showSnackbar("删除失败，请重试") } } },
                ) { CourseCard(app, c, onOpen = { onOpenCourse(c.id) }) }
            }
            item {
                // 未分类（按学期归属）
                if (selectedSemId != null) {
                    UncatCard(app, selectedSemId!!, onOpenHomework, onOpenUncat)
                }
            }
        }
    }

    if (showNewCourse) {
        AlertDialog(
            onDismissRequest = { showNewCourse = false; newCourseError = null },
            title = { Text("新建课程") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newCourseName,
                        onValueChange = { newCourseName = it; newCourseError = null },
                        label = { Text("课程名") },
                        singleLine = true,
                        modifier = Modifier.testTag("course_name_field"),
                    )
                    if (newCourseError != null) {
                        Text(newCourseError!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newCourseName.trim()
                    val semId = selectedSemId ?: return@TextButton
                    scope.launch {
                        if (name.isEmpty()) {
                            newCourseError = "请输入课程名"
                            return@launch
                        }
                        val dup = app.repo.db.courseDao().listAll().firstOrNull { it.name == name }
                        if (dup != null) {
                            newCourseError = if (dup.deletedAt != null) "回收站中有同名课程，请先恢复或彻底删除" else "已存在同名课程「$name」"
                            return@launch
                        }
                        app.repo.saveCourse(
                            Course(UUID.randomUUID().toString(), semId, name, (0..7).random())
                        )
                        newCourseName = ""
                        showNewCourse = false
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewCourse = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun LaunchedEffect2(key: Boolean, block: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key) {
        if (key) block()
    }
}

@Composable
private fun CourseCard(app: TaskraApp, c: Course, onOpen: () -> Unit) {
    val list by app.repo.observeByCourse(c.id).collectAsState(initial = emptyList())
    val total = list.size
    val undone = list.count { it.status == Status.TODO }
    val done = total - undone
    val marker = courseColor(c.colorIndex)
    val isDark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val markerText = lerp(marker, MaterialTheme.colorScheme.onSurface, if (isDark) .38f else .24f)

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(5.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawLine(
                        color = marker.copy(alpha = if (isDark) 0.78f else 0.70f),
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 3.5.dp.toPx(),
                    )
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(marker.copy(alpha = if (isDark) 0.24f else 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    c.name.take(1),
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = NotebookHand, fontSize = 21.sp),
                    color = markerText,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    c.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = NotebookHand, fontSize = 19.sp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "$undone 项待办 · $done 项完成" + if (!c.isActive) " · 已停用" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            )
        }
    }
}

@Composable
private fun UncatCard(app: TaskraApp, semesterId: String, onOpenHomework: (String) -> Unit, onOpenUncat: (String) -> Unit) {
    val uncat by app.repo.db.homeworkDao().observeUncatAll().collectAsState(initial = emptyList())
    if (uncat.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onOpenUncat(semesterId) },
        shape = RoundedCornerShape(5.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("未分类", style = MaterialTheme.typography.titleMedium.copy(fontFamily = NotebookHand, fontSize = 19.sp))
            Text("${uncat.size} 项作业", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            uncat.take(3).forEach { h ->
                Text(
                    "· ${h.title.ifBlank { "未命名作业" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenHomework(h.id) }.padding(vertical = 4.dp),
                )
            }
        }
    }
}
