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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import com.taskra.TaskraApp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsScreen(
    app: TaskraApp,
    onBack: () -> Unit,
    onContinueNew: () -> Unit,
    onContinueEdit: (String) -> Unit,
) {
    val drafts by app.repo.observeDrafts().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var discardId by remember { mutableStateOf<String?>(null) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("草稿箱") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "新建草稿不计入待办；提交成功后自动清理对应草稿。丢弃修改必须是明确操作。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (drafts.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface).padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("没有草稿") }
                }
            } else {
                items(drafts, key = { it.id }) { d ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                if (d.homeworkId == null) "新建草稿" else "编辑草稿",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                (d.title.ifBlank { "（无标题）" }) + " · " + fmt.format(Date(d.updatedAt)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    if (d.homeworkId == null) onContinueNew() else onContinueEdit(d.homeworkId)
                                }) { Text("继续编辑") }
                                OutlinedButton(onClick = { discardId = d.id }) { Text("丢弃") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (discardId != null) {
        AlertDialog(
            onDismissRequest = { discardId = null },
            title = { Text("丢弃这份草稿？") },
            text = { Text("明确丢弃后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val id = discardId!!
                    discardId = null
                    scope.launch { app.repo.deleteDraft(id) }
                }) { Text("丢弃") }
            },
            dismissButton = { TextButton(onClick = { discardId = null }) { Text("取消") } },
        )
    }
}
