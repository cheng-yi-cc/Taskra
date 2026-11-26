package com.taskra.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskra.TaskraApp
import com.taskra.ui.AppViewModel
import com.taskra.ui.components.NotebookEmpty
import com.taskra.ui.components.PaperBackButton
import com.taskra.ui.components.PaperTopBar as TopAppBar
import com.taskra.ui.components.SwipeToTrash
import com.taskra.ui.components.notebookPaper
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.TaskraMotion
import com.taskra.ui.motion.appleTactile
import com.taskra.ui.theme.NotebookHand
import kotlinx.coroutines.launch
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
    val reduced = LocalReduceMotion.current
    val drafts by app.repo.observeDrafts().collectAsState(initial = emptyList())
    val vm: AppViewModel = viewModel(factory = AppViewModel.Factory(app))
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var deleteFailed by remember { mutableStateOf(false) }
    var swipeReset by remember { mutableStateOf(0) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val listState = rememberLazyListState()
    val scrolled by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8 }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("草稿箱") },
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
            if (drafts.isEmpty()) {
                item(key = "drafts_empty") {
                    NotebookEmpty(
                        "没有草稿",
                        modifier = Modifier.animateItem(
                            fadeInSpec = TaskraMotion.listFadeSpec(reduced),
                            fadeOutSpec = TaskraMotion.listFadeSpec(reduced),
                            placementSpec = TaskraMotion.listPlacementSpec(reduced),
                        ),
                    )
                }
            } else {
                items(drafts, key = { it.id }) { d ->
                    val interaction = remember { MutableInteractionSource() }
                    val preview = remember(d.title, d.blocksJson) {
                        val blocks = com.taskra.data.decodeBlocks(d.blocksJson)
                        d.title.ifBlank {
                            blocks.firstOrNull { it.kind == "TEXT" && it.text.isNotBlank() }
                                ?.text?.trim()?.replace('\n', ' ')?.take(40)
                                ?: when {
                                    blocks.any { it.kind == "IMAGE" } -> "图片作业"
                                    blocks.any { it.kind == "AUDIO" } -> "录音作业"
                                    else -> "未完成的作业"
                                }
                        }
                    }
                    SwipeToTrash(
                        swipeRight = true,
                        deleteLabel = "删除草稿",
                        actionText = "删除草稿",
                        resetKey = swipeReset,
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = TaskraMotion.listFadeSpec(reduced),
                                fadeOutSpec = TaskraMotion.listFadeSpec(reduced),
                                placementSpec = TaskraMotion.listPlacementSpec(reduced),
                            )
                            .testTag("swipe_draft:${d.id}"),
                        onDelete = {
                            vm.deleteDraft(
                                id = d.id,
                                onSuccess = {
                                    scope.launch {
                                        snackbar.currentSnackbarData?.dismiss()
                                        val res = snackbar.showSnackbar(
                                            message = "已删除草稿",
                                            actionLabel = "撤销",
                                            duration = SnackbarDuration.Short,
                                        )
                                        if (res == SnackbarResult.ActionPerformed) {
                                            vm.restoreDraft(d) {
                                                scope.launch { snackbar.showSnackbar("恢复草稿失败，请重试") }
                                            }
                                            swipeReset++
                                        }
                                    }
                                },
                                onFailure = { swipeReset++; deleteFailed = true },
                            )
                        },
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .appleTactile(interaction, pressedScale = 0.982f, cornerRadius = 5.dp)
                                .testTag("continue_draft:${d.id}")
                                .clickable(interactionSource = interaction, indication = null) {
                                    if (d.homeworkId == null) onContinueNew(d.id) else onContinueEdit(d.homeworkId)
                                },
                            shape = RoundedCornerShape(5.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    preview,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = NotebookHand,
                                        fontSize = 18.sp,
                                        lineHeight = 24.sp,
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(7.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f))
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            if (d.homeworkId == null) "新建草稿" else "编辑草稿",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Text(
                                        fmt.format(Date(d.updatedAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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
