package com.taskra.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskra.TaskraApp
import com.taskra.ui.AppViewModel
import com.taskra.ui.components.NotebookEmpty
import com.taskra.ui.components.PaperTopBar as TopAppBar
import com.taskra.ui.components.SwipeToTrash
import com.taskra.ui.components.notebookPaper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsScreen(
    app: TaskraApp,
    onBack: () -> Unit,
    onContinueNew: (String) -> Unit,
    onContinueEdit: (String) -> Unit,
) {
    val drafts by app.repo.observeDrafts().collectAsState(initial = emptyList())
    val vm: AppViewModel = viewModel(factory = AppViewModel.Factory(app))
    var deleteFailed by remember { mutableStateOf(false) }
    var swipeReset by remember { mutableStateOf(0) }
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
            modifier = Modifier.fillMaxSize().notebookPaper().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp, 16.dp, 24.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (drafts.isEmpty()) {
                item {
                    NotebookEmpty("没有草稿")
                }
            } else {
                items(drafts, key = { it.id }) { d ->
                    SwipeToTrash(
                        swipeRight = true,
                        deleteLabel = "删除草稿",
                        resetKey = swipeReset,
                        modifier = Modifier.testTag("swipe_draft:${d.id}"),
                        onDelete = { vm.deleteDraft(d.id) { swipeReset++; deleteFailed = true } },
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth().testTag("continue_draft:${d.id}").clickable {
                                if (d.homeworkId == null) onContinueNew(d.id) else onContinueEdit(d.homeworkId)
                            },
                            shape = RoundedCornerShape(5.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    if (d.homeworkId == null) "新建草稿" else "编辑草稿",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    (d.title.ifBlank { com.taskra.data.decodeBlocks(d.blocksJson).firstOrNull { it.kind == "TEXT" && it.text.isNotBlank() }?.text?.take(28) ?: "未完成的作业" }) + " · " + fmt.format(Date(d.updatedAt)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (deleteFailed) AlertDialog(
        onDismissRequest = { deleteFailed = false },
        title = { Text("删除失败，请重试") },
        confirmButton = { TextButton(onClick = { deleteFailed = false }) { Text("确定") } },
    )
}
