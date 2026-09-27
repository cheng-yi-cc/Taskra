package com.taskra.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.taskra.TaskraApp
import com.taskra.data.BlockKind
import com.taskra.data.ReviewItem
import com.taskra.data.ReviewResult
import com.taskra.data.ReviewSession
import com.taskra.data.SessionStatus
import com.taskra.ui.AppViewModel
import com.taskra.util.postOnMain
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    app: TaskraApp,
    vm: AppViewModel,
    sessionId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val session by app.repo.observeSession(sessionId).collectAsState(initial = null)
    val items by app.repo.observeSessionItems(sessionId).collectAsState(initial = emptyList())

    val ids: List<String> = remember(session) {
        try {
            if (session?.homeworkIdsJson.isNullOrBlank()) emptyList()
            else Json.decodeFromString<List<String>>(session!!.homeworkIdsJson)
        } catch (_: Exception) {
            emptyList()
        }
    }
    val resultMap = remember(items) { items.associate { it.homeworkId to it.result } }
    val practiced = resultMap.count { it.value != ReviewResult.NONE }
    val needRetry = resultMap.count { it.value == ReviewResult.NEED_RETRY }
    val idx = (session?.currentIndex ?: 0).coerceIn(0, (ids.size - 1).coerceAtLeast(0))
    val currentId = ids.getOrNull(idx)
    var detail by remember { mutableStateOf<com.taskra.data.HomeworkDetail?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    LaunchedEffect(currentId) {
        if (currentId == null) {
            detail = null
        } else {
            val d = app.repo.detail(currentId)
            detail = d
            // 被移入回收站或彻底删除 → 标明不可用并可跳过
            unavailable = d == null || d.homework.deletedAt != null
        }
    }

    fun go(i: Int) {
        val s = session ?: return
        scope.launch { app.repo.saveSession(s.copy(currentIndex = i.coerceIn(0, (ids.size - 1).coerceAtLeast(0)))) }
    }

    fun mark(result: String) {
        val id = currentId ?: return
        scope.launch {
            // 重复点击更新当前结果，不反复增加练习次数
            app.repo.saveItem(ReviewItem(sessionId, id, result, System.currentTimeMillis()))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("重新练习") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        if (session == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("轮次不存在")
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "本轮已练${practiced}/${ids.size}项，其中${needRetry}项还需要再练。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (ids.isEmpty()) 0f else practiced.toFloat() / ids.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text("第 ${if (ids.isEmpty()) 0 else idx + 1} / ${ids.size} 项", style = MaterialTheme.typography.labelLarge)
            }
            if (unavailable) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            "本轮这项作业已被移入回收站或删除，跳过即可，不计入已练。",
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            } else if (detail != null) {
                val d = detail!!
                item {
                    Text(d.homework.title.ifBlank { "未命名作业" }, style = MaterialTheme.typography.titleLarge)
                }
                item {
                    Text("题目", style = MaterialTheme.typography.titleMedium)
                }
                val q = d.question
                if (q.isEmpty()) {
                    item { Text("无题目内容") }
                } else {
                    items(q.size) { i ->
                        BlockView(app, q[i], onClickImage = {})
                        Spacer(Modifier.height(8.dp))
                    }
                }
                item {
                    val cur = currentId?.let { resultMap[it] } ?: ReviewResult.NONE
                    Text(
                        "当前结果：" + when (cur) {
                            ReviewResult.MASTERED -> "这次会做了"
                            ReviewResult.NEED_RETRY -> "还需要再练"
                            else -> "尚未标记"
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { mark(ReviewResult.MASTERED) }, modifier = Modifier.weight(1f)) {
                            Text("这次会做了")
                        }
                        Button(onClick = { mark(ReviewResult.NEED_RETRY) }, modifier = Modifier.weight(1f)) {
                            Text("还需要再练")
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { go(idx - 1) }, enabled = idx > 0, modifier = Modifier.weight(1f)) {
                        Text("上一项")
                    }
                    OutlinedButton(
                        onClick = { go(idx + 1) },
                        enabled = idx < ids.size - 1,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("下一项")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("上一项/下一项只是浏览，不自动标记练过。", style = MaterialTheme.typography.labelSmall)
            }
            item {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            session?.let { app.repo.saveSession(it.copy(status = SessionStatus.FINISHED, finishedAt = System.currentTimeMillis())) }
                            postOnMain { onBack() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("结束本轮") }
            }
        }
    }
}
