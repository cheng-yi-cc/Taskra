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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.taskra.TaskraApp
import com.taskra.data.BlockKind
import com.taskra.data.ContentBlock
import com.taskra.data.Status
import com.taskra.ui.AppViewModel
import com.taskra.util.TimeUtils
import com.taskra.util.buildAnnotated
import com.taskra.util.decodeSpans
import com.taskra.util.rememberHighlightColor
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkDetailScreen(
    app: TaskraApp,
    vm: AppViewModel,
    homeworkId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onViewImage: (String, Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var detail by remember { mutableStateOf<com.taskra.data.HomeworkDetail?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    // 刷新：监听 DB 变化
    val flowH by app.repo.observeHomework(homeworkId).collectAsState(initial = null)
    LaunchedEffect(homeworkId, flowH) {
        detail = app.repo.detail(homeworkId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("作业详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onEdit(homeworkId) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val d = detail
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("作业不存在或已被彻底删除")
            }
            return@Scaffold
        }
        val h = d.homework
        val overdue = h.status == Status.TODO &&
            TimeUtils.isOverdue(h.deadlineType, h.deadlineDate, h.deadlineTime, h.deadlineZoneId)
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(h.title.ifBlank { "未命名作业" }, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    (d.course?.name ?: "未分类") + " · " + (d.semester?.name ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    TimeUtils.formatDeadline(h.deadlineType, h.deadlineDate, h.deadlineTime, h.deadlineZoneId) +
                        (if (!h.deadlineZoneId.isNullOrBlank() && h.deadlineType != "NONE") "（${h.deadlineZoneId}）" else "") +
                        (if (overdue) " · 已逾期" else "") +
                        (if (h.status == Status.DONE) " · 已完成" else ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text("题目与要求", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
            }
            if (d.question.isEmpty()) {
                item { Text("暂无内容", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                val qImages = d.question.filter { it.kind == BlockKind.IMAGE }
                items(d.question.size) { i ->
                    BlockView(app, d.question[i], onClickImage = {
                        onViewImage(homeworkId, qImages.indexOf(d.question[i]).coerceAtLeast(0))
                    })
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                if (h.status == Status.TODO) {
                    Button(
                        onClick = {
                            scope.launch {
                                app.repo.markDone(homeworkId)
                                detail = app.repo.detail(homeworkId)
                                snackbar.showSnackbar("已完成")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("标记完成") }
                } else {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                app.repo.markTodo(homeworkId)
                                detail = app.repo.detail(homeworkId)
                                snackbar.showSnackbar("已恢复为未完成")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("恢复为未完成") }
                }
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除作业？") },
            text = { Text("将移入回收站，可恢复全部内容。") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        app.repo.moveToTrash(homeworkId)
                    }
                    onBack()
                }) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
fun BlockView(app: TaskraApp, b: ContentBlock, onClickImage: () -> Unit) {
    if (b.kind == BlockKind.TEXT) {
        if (!b.text.isNullOrBlank()) {
            val highlight = rememberHighlightColor()
            val baseSize = MaterialTheme.typography.bodyLarge.fontSize
            val annotated = remember(b.text, b.spanJson) {
                buildAnnotated(b.text, decodeSpans(b.spanJson), highlight, baseSize)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Text(
                    annotated,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    } else {
        val assetId = b.imageId
        if (assetId != null) {
            // 按需加载：只加载当前项，不一次解码全部原图（Coil 按需加载）
            val f = remember(assetId) {
                // 同步查相对路径需 IO；此处用约定路径 images/<id>.jpg
                File(app.filesDir, "images/$assetId.jpg")
            }
            AsyncImage(
                model = f,
                contentDescription = "作业图片",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onClickImage),
                contentScale = ContentScale.FillWidth, // 保持比例，不裁掉题号公式
            )
        }
    }
}
