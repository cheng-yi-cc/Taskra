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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.taskra.TaskraApp
import com.taskra.data.Course
import com.taskra.data.Semester
import com.taskra.data.Status
import com.taskra.ui.AppViewModel
import com.taskra.ui.components.HomeworkCard
import com.taskra.ui.TodoUiItem
import com.taskra.util.TimeUtils
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
    var showNewCourse by remember { mutableStateOf(false) }
    var newCourseName by remember { mutableStateOf("") }
    var newCourseError by remember { mutableStateOf<String?>(null) }
    var newSemesterName by remember { mutableStateOf("") }
    var showNewSemester by remember { mutableStateOf(false) }

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

    val semCourses = remember(courses, selectedSemId) {
        courses.filter { it.semesterId == selectedSemId }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewCourse = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "新建课程")
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("课程档案", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "按学期 → 课程 → 作业组织。完成作业只改变状态，不删除内容。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    semesters.forEach { s ->
                        FilterChip(
                            selected = selectedSemId == s.id,
                            onClick = { selectedSemId = s.id },
                            label = { Text((if (s.isArchived) "📦 " else "") + s.name) },
                        )
                    }
                }
                if (semesters.isEmpty()) Text("正在准备学期…")
            }
            items(semCourses, key = { it.id }) { c ->
                CourseCard(app, c, onOpen = { onOpenCourse(c.id) })
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
                    Text(
                        "将创建在：${semesters.find { it.id == selectedSemId }?.name ?: "默认学期"}",
                        style = MaterialTheme.typography.labelSmall,
                    )
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
                        val dup = app.repo.findDuplicateCourse(semId, name)
                        if (dup != null) {
                            newCourseError = "该学期已存在同名课程「$name」，请换个名字或直接使用它"
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
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(com.taskra.ui.theme.courseColor(c.colorIndex).copy(alpha = 0.2f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(c.name.take(1), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "共 $total · 未完成 $undone · 已完成 $done" + if (!c.isActive) " · 已停用" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UncatCard(app: TaskraApp, semesterId: String, onOpenHomework: (String) -> Unit, onOpenUncat: (String) -> Unit) {
    val todo by app.repo.observeTodo().collectAsState(initial = emptyList())
    val uncat = remember(todo) { todo.filter { it.courseId == null && it.semesterId == semesterId } }
    if (uncat.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpenUncat(semesterId) },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("未分类（本学期）", style = MaterialTheme.typography.titleMedium)
            Text("${uncat.size} 项待办尚未归入课程，点击查看全部", style = MaterialTheme.typography.bodyMedium)
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
