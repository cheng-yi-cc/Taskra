package com.taskra.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taskra.TaskraApp
import com.taskra.ui.AppViewModel
import com.taskra.ui.TodoUiItem
import com.taskra.ui.components.HomeworkCard
import com.taskra.ui.components.NotebookEmpty
import com.taskra.ui.components.PaperBackButton
import com.taskra.ui.components.PaperTopBar as TopAppBar
import com.taskra.ui.components.notebookPaper
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.RollingCountText
import com.taskra.ui.motion.TaskraMotion
import com.taskra.util.TimeUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(app: TaskraApp, vm: AppViewModel, onBack: () -> Unit, onOpenHomework: (String) -> Unit) {
    val reduced = LocalReduceMotion.current
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
    val listState = rememberLazyListState()
    val scrolled by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8 }
    }

    val dangerOutline = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
    val dangerButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    val dangerTextColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                scrolled = scrolled,
                navigationIcon = { PaperBackButton(onClick = onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().notebookPaper().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp, 16.dp, 24.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "trash_clear_bar") {
                AnimatedVisibility(
                    visible = trash.isNotEmpty() || trashedCourses.isNotEmpty(),
                    enter = TaskraMotion.expandEnter(reduced),
                    exit = TaskraMotion.collapseExit(reduced),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { confirmClear = true },
                            colors = dangerButtonColors,
                            border = dangerOutline,
                        ) { Text("清空回收站") }
                    }
                }
            }
            if (trash.isEmpty() && trashedCourses.isEmpty()) {
                item(key = "trash_empty") {
                    NotebookEmpty(
                        "回收站是空的",
                        modifier = Modifier.animateItem(
                            fadeInSpec = TaskraMotion.listFadeSpec(reduced),
                            fadeOutSpec = TaskraMotion.listFadeSpec(reduced),
                            placementSpec = TaskraMotion.listPlacementSpec(reduced),
                        ),
                    )
                }
            } else {
                items(trashedCourses, key = { "course:${it.id}" }) { course ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(
                                fadeInSpec = TaskraMotion.listFadeSpec(reduced),
                                fadeOutSpec = TaskraMotion.listFadeSpec(reduced),
                                placementSpec = TaskraMotion.listPlacementSpec(reduced),
                            ),
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(course.name, style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RollingCountText(
                                    trash.count { it.courseId == course.id },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    " 项作业",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        try {
                                            app.repo.restoreCourse(course.id)
                                            snackbar.showSnackbar("已恢复课程")
                                        } catch (_: Exception) {
                                            snackbar.showSnackbar("恢复失败，请重试")
                                        }
                                    }
                                }) { Text("恢复课程") }
                                OutlinedButton(
                                    onClick = { confirmCourseDelete = course.id },
                                    colors = dangerButtonColors,
                                    border = dangerOutline,
                                ) { Text("彻底删除") }
                            }
                        }
                    }
                }
                items(standaloneTrash, key = { it.id }) { h ->
                    val c = h.courseId?.let { courseMap[it] }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(
                                fadeInSpec = TaskraMotion.listFadeSpec(reduced),
                                fadeOutSpec = TaskraMotion.listFadeSpec(reduced),
                                placementSpec = TaskraMotion.listPlacementSpec(reduced),
                            ),
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            HomeworkCard(
                                item = TodoUiItem(
                                    h, c?.name, c?.colorIndex ?: 0, false,
                                    TimeUtils.formatDeadline(h.deadlineType, h.deadlineDate, h.deadlineTime, h.deadlineZoneId),
                                    "", 0,
                                ),
                                done = h.status == "DONE",
                                showCheck = false,
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
                                OutlinedButton(
                                    onClick = { confirmDelete = h.id },
                                    colors = dangerButtonColors,
                                    border = dangerOutline,
                                ) { Text("彻底删除") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmCourseDelete != null) {
        val targetId = confirmCourseDelete!!
        val targetCourse = trashedCourses.find { it.id == targetId }
        val hwCount = trash.count { it.courseId == targetId }
        AlertDialog(
            onDismissRequest = { confirmCourseDelete = null },
            title = { Text("彻底删除课程？") },
            text = {
                Text(
                    buildString {
                        append("将彻底删除")
                        if (targetCourse != null) append("「${targetCourse.name}」") else append("该课程")
                        if (hwCount > 0) append("及其 $hwCount 项作业与附件")
                        append("，删除后无法恢复。")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    colors = dangerTextColors,
                    onClick = {
                        confirmCourseDelete = null
                        scope.launch {
                            try {
                                app.repo.deleteCourseForever(targetId, app.images)
                                snackbar.showSnackbar("已彻底删除")
                            } catch (_: Exception) {
                                snackbar.showSnackbar("删除失败，请重试")
                            }
                        }
                    },
                ) { Text("彻底删除") }
            },
            dismissButton = { TextButton(onClick = { confirmCourseDelete = null }) { Text("取消") } },
        )
    }
    if (confirmDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("彻底删除这项作业？") },
            text = { Text("彻底删除后将同时清理其中的图片和录音，无法恢复。") },
            confirmButton = {
                TextButton(
                    colors = dangerTextColors,
                    onClick = {
                        val id = confirmDelete!!
                        confirmDelete = null
                        scope.launch {
                            app.repo.deleteForever(id, app.images)
                            snackbar.showSnackbar("已彻底删除")
                        }
                    },
                ) { Text("彻底删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
    if (confirmClear) {
        val summaryText = buildList {
            if (trashedCourses.isNotEmpty()) add("${trashedCourses.size} 门课程")
            if (standaloneTrash.isNotEmpty()) add("${standaloneTrash.size} 项作业")
        }.joinToString("和")
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("永久清空回收站？") },
            text = { Text("将彻底删除${summaryText.ifBlank { "全部内容" }}及其附件，无法恢复。") },
            confirmButton = {
                TextButton(
                    colors = dangerTextColors,
                    onClick = {
                        confirmClear = false
                        scope.launch {
                            trashedCourses.forEach { app.repo.deleteCourseForever(it.id, app.images) }
                            standaloneTrash.forEach { app.repo.deleteForever(it.id, app.images) }
                            snackbar.showSnackbar("回收站已清空")
                        }
                    },
                ) { Text("永久清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}
