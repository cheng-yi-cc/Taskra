package com.taskra.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.taskra.TaskraApp
import com.taskra.data.BackupSummary
import com.taskra.data.Semester
import com.taskra.data.ThemeMode
import com.taskra.ui.AppViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: TaskraApp,
    vm: AppViewModel,
    onOpenTrash: () -> Unit,
    onOpenDrafts: () -> Unit,
) {
    val prefs by vm.prefs.collectAsState()
    val semesters by vm.semesters.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showNewSem by remember { mutableStateOf(false) }
    var newSemName by remember { mutableStateOf("") }
    var renameSem by remember { mutableStateOf<Semester?>(null) }
    var renameText by remember { mutableStateOf("") }
    var backupInfo by remember { mutableStateOf<BackupSummary?>(null) }
    var backupUriPending by remember { mutableStateOf<android.net.Uri?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var mottoDraft by remember { mutableStateOf(prefs.mottoText) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                app.backup.exportTo(uri)
                app.prefs.setLastBackupTime(System.currentTimeMillis())
                snackbar.showSnackbar("备份导出成功")
            } catch (e: Exception) {
                snackbar.showSnackbar("导出失败：${e.message}（最近备份时间未更新）")
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val summary = app.backup.inspectBackup(uri)
                backupInfo = summary
                backupUriPending = uri
                showRestoreConfirm = true
            } catch (e: Exception) {
                snackbar.showSnackbar("备份校验失败：${e.message}，未改动现有数据")
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("设置", style = MaterialTheme.typography.titleLarge)
            }
            // 学期管理
            item {
                SettingCard("学期管理") {
                    semesters.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name + if (s.isArchived) "（已归档）" else "")
                                Text(
                                    if (s.id == prefs.currentSemesterId) "默认新建学期" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            TextButton(onClick = { renameSem = s; renameText = s.name }) { Text("重命名") }
                            TextButton(onClick = {
                                scope.launch {
                                    app.repo.saveSemester(s.copy(isArchived = !s.isArchived))
                                }
                            }) { Text(if (s.isArchived) "取消归档" else "归档") }
                            TextButton(onClick = {
                                scope.launch { app.prefs.setCurrentSemester(s.id) }
                            }) { Text("设为默认") }
                        }
                    }
                    TextButton(onClick = { showNewSem = true }) { Text("＋ 新建学期") }
                    Text(
                        "归档只是不再作为默认新建学期，不删除作业、不自动完成，也不会隐藏往期未完成作业。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            // 自励文字
            item {
                SettingCard("给自己的话") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("在待办页显示", modifier = Modifier.weight(1f))
                        Switch(
                            checked = prefs.mottoVisible,
                            onCheckedChange = { vm.setMottoVisible(it) },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = mottoDraft,
                        onValueChange = {
                            mottoDraft = it
                            if (it != prefs.mottoText && it.length <= 300) {
                                // 输入暂停由保存按钮统一提交；此处实时校验长度
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6,
                        supportingText = { Text("${mottoDraft.length}/300") },
                    )
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                app.prefs.setMotto(mottoDraft.take(300))
                                snackbar.showSnackbar("已保存")
                            }
                        }) { Text("保存") }
                        TextButton(onClick = {
                            mottoDraft = prefs.mottoText
                        }) { Text("取消") }
                        TextButton(onClick = {
                            scope.launch {
                                app.prefs.setMotto("")
                                mottoDraft = ""
                            }
                        }) { Text("清空") }
                    }
                }
            }
            // 外观
            item {
                SettingCard("外观") {
                    Text("主题")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = prefs.themeMode == ThemeMode.SYSTEM, onClick = { vm.setTheme(ThemeMode.SYSTEM) }, label = { Text("跟随系统") })
                        FilterChip(selected = prefs.themeMode == ThemeMode.LIGHT, onClick = { vm.setTheme(ThemeMode.LIGHT) }, label = { Text("浅色") })
                        FilterChip(selected = prefs.themeMode == ThemeMode.DARK, onClick = { vm.setTheme(ThemeMode.DARK) }, label = { Text("深色") })
                    }
                }
            }
            // 备份恢复
            item {
                SettingCard("备份与恢复") {
                    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                    Text(
                        if (prefs.lastBackupTime > 0) "最近备份：${fmt.format(Date(prefs.lastBackupTime))}"
                        else "尚未备份（仅成功导出后更新）",
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            exportLauncher.launch("taskra-backup-${System.currentTimeMillis()}.zip")
                        }) { Text("导出完整备份") }
                        TextButton(onClick = { importLauncher.launch(arrayOf("application/zip", "*/*")) }) {
                            Text("从备份恢复")
                        }
                    }
                    Text(
                        "备份包含学期、课程、作业、图文顺序、原图、复习记录、草稿、回收站与自励文字等设置。恢复为覆盖模式，请先确认。备份未加密，请妥善保管；卸载应用可能丢失未导出的资料。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            item {
                SettingCard("回收站与草稿") {
                    Text("回收站", modifier = Modifier.clickable(onClick = onOpenTrash).padding(8.dp), color = MaterialTheme.colorScheme.primary)
                    Text("草稿箱", modifier = Modifier.clickable(onClick = onOpenDrafts).padding(8.dp), color = MaterialTheme.colorScheme.primary)
                }
            }
            item {
                Text(
                    "课迹 Taskra · 本地优先，离线可用。不含账号、同步、广告与追踪。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showNewSem) {
        AlertDialog(
            onDismissRequest = { showNewSem = false },
            title = { Text("新建学期") },
            text = {
                OutlinedTextField(value = newSemName, onValueChange = { newSemName = it }, label = { Text("如 2026 秋季") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val name = newSemName.trim()
                        if (name.isEmpty()) return@launch
                        val id = UUID.randomUUID().toString()
                        app.repo.saveSemester(Semester(id, name, false, semesters.size))
                        newSemName = ""
                        showNewSem = false
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewSem = false }) { Text("取消") } },
        )
    }
    if (renameSem != null) {
        AlertDialog(
            onDismissRequest = { renameSem = null },
            title = { Text("重命名学期") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val s = renameSem ?: return@TextButton
                    scope.launch {
                        if (renameText.trim().isNotEmpty()) {
                            app.repo.saveSemester(s.copy(name = renameText.trim()))
                        }
                        renameSem = null
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameSem = null }) { Text("取消") } },
        )
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
                        "课程 ${info.courseCount} · 作业 ${info.homeworkCount} · 图片 ${info.imageCount} · " +
                        (if (info.hasDrafts) "含草稿" else "无草稿") +
                        "\n\n恢复将覆盖当前全部数据。已先做校验，导入失败会保持旧数据可用。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    val uri = backupUriPending ?: return@TextButton
                    scope.launch {
                        try {
                            app.backup.restoreFrom(uri, confirmed = true, imageStore = app.images)
                            snackbar.showSnackbar("恢复成功")
                        } catch (e: Exception) {
                            snackbar.showSnackbar("恢复失败，旧数据仍可用：${e.message}")
                        }
                    }
                }) { Text("确认覆盖") }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
