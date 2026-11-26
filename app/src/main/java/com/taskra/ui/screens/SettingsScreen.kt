package com.taskra.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taskra.TaskraApp
import com.taskra.data.BackupSummary
import com.taskra.data.ThemeMode
import com.taskra.ui.AppViewModel
import com.taskra.ui.components.notebookPaper
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.TaskraMotion
import com.taskra.ui.motion.appleContentSize
import com.taskra.ui.motion.appleTactile
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: TaskraApp,
    vm: AppViewModel,
    onOpenTrash: () -> Unit,
) {
    val prefs by vm.prefs.collectAsState()
    val updateState by app.updater.state.collectAsState()
    val trash by app.repo.observeTrash().collectAsState(initial = emptyList())
    val trashedCourses by app.repo.observeTrashedCourses().collectAsState(initial = emptyList())
    val trashCount = remember(trash, trashedCourses) {
        val groupedCourseIds = trashedCourses.map { it.id }.toSet()
        trashedCourses.size + trash.count { it.courseId !in groupedCourseIds }
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var backupInfo by remember { mutableStateOf<BackupSummary?>(null) }
    var backupUriPending by remember { mutableStateOf<android.net.Uri?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var showMottoEdit by remember { mutableStateOf(false) }
    var mottoDraft by remember { mutableStateOf(TextFieldValue("")) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exporting = true
            try {
                app.backup.exportTo(uri)
                app.prefs.setLastBackupTime(System.currentTimeMillis())
                snackbar.showSnackbar("备份导出成功")
            } catch (e: Exception) {
                snackbar.showSnackbar("导出失败：${e.message}（最近备份时间未更新）")
            } finally {
                exporting = false
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            try {
                val summary = app.backup.inspectBackup(uri)
                backupInfo = summary
                backupUriPending = uri
                showRestoreConfirm = true
            } catch (e: Exception) {
                snackbar.showSnackbar("备份校验失败：${e.message}，未改动现有数据")
            } finally {
                importing = false
            }
        }
    }

    val reduced = LocalReduceMotion.current
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().notebookPaper().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp, 16.dp, 24.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                com.taskra.ui.components.NotebookHeading("设置")
            }
            // 外观
            item {
                SettingCard("外观") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = prefs.themeMode == ThemeMode.SYSTEM, onClick = { vm.setTheme(ThemeMode.SYSTEM) }, label = { Text("跟随系统") })
                        FilterChip(selected = prefs.themeMode == ThemeMode.LIGHT, onClick = { vm.setTheme(ThemeMode.LIGHT) }, label = { Text("浅色") })
                        FilterChip(selected = prefs.themeMode == ThemeMode.DARK, onClick = { vm.setTheme(ThemeMode.DARK) }, label = { Text("深色") })
                    }
                    AnimatedVisibility(
                        visible = prefs.themeMode == ThemeMode.SYSTEM,
                        enter = TaskraMotion.expandEnter(reduced),
                        exit = TaskraMotion.collapseExit(reduced),
                    ) {
                        Column {
                            Spacer(Modifier.height(4.dp))
                            // 让“跟随系统”是否真的生效可见：系统给的颜色是深还是浅。
                            val systemDark = MaterialTheme.colorScheme.surface.luminance() < .5f
                            Text(
                                "系统当前：${if (systemDark) "深色" else "浅色"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            // 自励文字
            item {
                SettingCard("给自己的话") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier
                                .weight(1f)
                                .then(
                                    if (prefs.mottoVisible) Modifier.clickable {
                                        mottoDraft = TextFieldValue(prefs.mottoText, TextRange(prefs.mottoText.length))
                                        showMottoEdit = true
                                    } else Modifier
                                ),
                        ) {
                            Text("在待办页显示")
                            AnimatedVisibility(
                                visible = prefs.mottoVisible,
                                enter = TaskraMotion.expandEnter(reduced),
                                exit = TaskraMotion.collapseExit(reduced),
                            ) {
                                Text(
                                    prefs.mottoText.ifBlank { "先做好眼前这一件。" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = prefs.mottoVisible,
                            enter = TaskraMotion.popEnter(reduced),
                            exit = TaskraMotion.popExit(reduced),
                        ) {
                            TextButton(onClick = {
                                mottoDraft = TextFieldValue(prefs.mottoText, TextRange(prefs.mottoText.length))
                                showMottoEdit = true
                            }) { Text("编辑") }
                        }
                        Switch(
                            checked = prefs.mottoVisible,
                            onCheckedChange = { vm.setMottoVisible(it) },
                        )
                    }
                }
            }
            item {
                SettingCard("作业提醒") {
                    com.taskra.ui.components.ReminderSettings(app, vm)
                }
            }
            item {
                SettingCard("多端同步") {
                    com.taskra.ui.components.SyncSettings(app) { msg ->
                        scope.launch { snackbar.showSnackbar(msg) }
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(5.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).appleContentSize()) {
                        SettingAction(
                            icon = Icons.Outlined.DeleteOutline,
                            label = "回收站",
                            trailingText = if (trashCount > 0) "$trashCount 项" else null,
                            onClick = onOpenTrash,
                        )
                    }
                }
            }
            // 备份恢复
            item {
                SettingCard("备份与恢复") {
                    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                    Text(
                        if (prefs.lastBackupTime > 0) "最近备份：${fmt.format(Date(prefs.lastBackupTime))}"
                        else "尚未备份",
                    )
                    Spacer(Modifier.height(6.dp))
                    SettingAction(
                        icon = Icons.Outlined.FileUpload,
                        label = if (exporting) "正在导出备份…" else "导出备份",
                        loading = exporting,
                        enabled = !exporting && !importing && !restoring,
                    ) {
                        exportLauncher.launch("taskra-backup-${System.currentTimeMillis()}.zip")
                    }
                    SettingAction(
                        icon = Icons.Outlined.FileDownload,
                        label = when {
                            restoring -> "正在恢复备份…"
                            importing -> "正在校验备份…"
                            else -> "恢复备份"
                        },
                        loading = importing || restoring,
                        enabled = !exporting && !importing && !restoring,
                    ) {
                        importLauncher.launch(arrayOf("application/zip", "*/*"))
                    }
                }
            }
            // 版本与更新
            item {
                SettingCard("版本与更新") {
                    val avail = updateState.availableUpdate
                    Text(
                        if (avail != null) "当前版本：v${updateState.currentVersion}（发现新版本 v${avail.versionName}）"
                        else "当前版本：v${updateState.currentVersion}",
                        color = if (avail != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    SettingAction(
                        icon = Icons.Outlined.SystemUpdateAlt,
                        label = when {
                            updateState.checking -> "正在检查更新…"
                            avail != null -> "查看新版本（v${avail.versionName}）"
                            else -> "检查更新"
                        },
                        loading = updateState.checking,
                    ) {
                        if (avail != null) {
                            app.updater.openUpdateDialog()
                        } else {
                            app.updater.checkManually { msg ->
                                scope.launch { snackbar.showSnackbar(msg) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRestoreConfirm && backupInfo != null) {
        val info = backupInfo!!
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("确认覆盖恢复？") },
            text = {
                Text(
                    "备份时间：${fmt.format(Date(info.createdAt))}\n" +
                        "课程 ${info.courseCount} · 作业 ${info.homeworkCount} · 图片 ${info.imageCount} · 录音 ${info.audioCount} · " +
                        (if (info.hasDrafts) "含草稿" else "无草稿") +
                        "\n\n恢复将覆盖当前设备上的全部数据，建议先导出备份。"
                )
            },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showRestoreConfirm = false
                        val uri = backupUriPending ?: return@TextButton
                        scope.launch {
                            restoring = true
                            try {
                                app.backup.restoreFrom(uri, confirmed = true, imageStore = app.images)
                                snackbar.showSnackbar("恢复成功")
                            } catch (e: Exception) {
                                snackbar.showSnackbar("恢复失败，旧数据仍可用：${e.message}")
                            } finally {
                                restoring = false
                            }
                        }
                    },
                ) { Text("确认覆盖") }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("取消") } },
        )
    }

    if (showMottoEdit) {
        val mottoFocus = remember { FocusRequester() }
        var focusRequested by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showMottoEdit = false },
            title = { Text("给自己的话") },
            text = {
                OutlinedTextField(
                    value = mottoDraft,
                    onValueChange = { if (it.text.length <= 300) mottoDraft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(mottoFocus)
                        .onGloballyPositioned {
                            if (!focusRequested) {
                                focusRequested = true
                                mottoFocus.requestFocus()
                            }
                        },
                    placeholder = { Text("写一句给自己的话…") },
                    minLines = 3,
                    maxLines = 8,
                    supportingText = { Text("${mottoDraft.text.length}/300") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.prefs.setMotto(mottoDraft.text) }
                    showMottoEdit = false
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (mottoDraft.text.isNotEmpty()) {
                        TextButton(onClick = {
                            scope.launch { app.prefs.setMotto("") }
                            showMottoEdit = false
                        }) { Text("清空") }
                    }
                    TextButton(onClick = { showMottoEdit = false }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(Modifier.padding(20.dp).appleContentSize()) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingAction(
    icon: ImageVector,
    label: String,
    loading: Boolean = false,
    enabled: Boolean = true,
    trailingText: String? = null,
    onClick: () -> Unit,
) {
    val reduced = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .appleTactile(interaction, pressedScale = 0.985f, cornerRadius = 6.dp, enabled = enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = loading,
            transitionSpec = { TaskraMotion.iconSwapSpec(reduced) },
            label = "settingActionIcon",
        ) { isLoading ->
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(14.dp))
        AnimatedContent(
            targetState = label,
            transitionSpec = { TaskraMotion.crossfadeSpec(reduced) },
            modifier = Modifier.weight(1f),
            label = "settingActionLabel",
        ) { text ->
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
        if (!trailingText.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                trailingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
