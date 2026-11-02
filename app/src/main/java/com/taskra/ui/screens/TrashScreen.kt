package com.taskra.ui.screens

import com.taskra.ui.components.notebookPaper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.taskra.ui.components.PaperTopBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.taskra.TaskraApp
import com.taskra.ui.AppViewModel
import com.taskra.ui.TodoUiItem
import com.taskra.ui.components.HomeworkCard
import com.taskra.util.TimeUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(app: TaskraApp, vm: AppViewModel, onBack: () -> Unit, onOpenHomework: (String) -> Unit) {
    val trash by app.repo.observeTrash().collectAsState(initial = emptyList())
    val courses by vm.coursesAll.collectAsState()
    val trashedCourses by app.repo.observeTrashedCourses().collectAsState(initial = emptyList())
    val groupedCourseIds = trashedCourses.map { it.id }.toSet()
    val standaloneTrash = trash.filter { it.courseId !in groupedCourseIds }
    val courseMap = remember(courses, trashedCourses) { (courses + trashedCourses).associateBy { it.id } }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmCourseDelete by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().notebookPaper().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp, 16.dp, 24.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (trash.isNotEmpty() || trashedCourses.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmClear = true }) { Text("清空回收站") }
                    }
                }
            }
            if (trash.isEmpty() && trashedCourses.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.surface).padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("回收站是空的") }
                }
            } else {
                items(trashedCourses, key = { "course:${it.id}" }) { course ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surface).padding(20.dp)) {
                        Text(course.name, style = MaterialTheme.typography.titleMedium)
                        Text("${trash.count { it.courseId == course.id }} 项作业", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { scope.launch {
                                try { app.repo.restoreCourse(course.id) }
                                catch (_: Exception) { snackbar.showSnackbar("恢复失败，请重试") }
                            } }) { Text("恢复课程") }
                            OutlinedButton(onClick = { confirmCourseDelete = course.id }) { Text("彻底删除") }
                        }
                    }
                }
                items(standaloneTrash, key = { it.id }) { h ->
                    val c = h.courseId?.let { courseMap[it] }
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.surface).padding(12.dp),
                    ) {
                        HomeworkCard(
                            item = TodoUiItem(
                                h, c?.name, c?.colorIndex ?: 0, false,
                                TimeUtils.formatDeadline(h.deadlineType, h.deadlineDate, h.deadlineTime, h.deadlineZoneId),
                                "", 0,
                            ),
                            done = h.status == "DONE",
                            onOpen = { onOpenHomework(h.id) },
                            onToggleDone = {},
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    app.repo.restoreFromTrash(h.id)
                                    snackbar.showSnackbar("已恢复")
                                }
                            }) { Text("恢复") }
                            OutlinedButton(onClick = { confirmDelete = h.id }) { Text("彻底删除") }
                        }
                    }
                }
            }
        }
    }

    if (confirmCourseDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmCourseDelete = null },
            title = { Text("彻底删除课程及全部作业？") },
            confirmButton = { TextButton(onClick = {
                val id = confirmCourseDelete ?: return@TextButton
                confirmCourseDelete = null
                scope.launch {
                    try { app.repo.deleteCourseForever(id, app.images) }
                    catch (_: Exception) { snackbar.showSnackbar("删除失败，请重试") }
                }
            }) { Text("彻底删除") } },
            dismissButton = { TextButton(onClick = { confirmCourseDelete = null }) { Text("取消") } },
        )
    }
    if (confirmDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("彻底删除？") },

            confirmButton = {
                TextButton(onClick = {
                    val id = confirmDelete!!
                    confirmDelete = null
                    scope.launch {
                        app.repo.deleteForever(id, app.images)
                        snackbar.showSnackbar("已彻底删除")
                    }
                }) { Text("彻底删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("永久清空回收站？") },

            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        trashedCourses.forEach { app.repo.deleteCourseForever(it.id, app.images) }
                        standaloneTrash.forEach { app.repo.deleteForever(it.id, app.images) }
                        snackbar.showSnackbar("回收站已清空")
                    }
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}
