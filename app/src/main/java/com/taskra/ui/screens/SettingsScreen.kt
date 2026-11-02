package com.taskra.ui.screens

import com.taskra.ui.components.notebookPaper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
) {
    val prefs by vm.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var backupInfo by remember { mutableStateOf<BackupSummary?>(null) }
    var backupUriPending by remember { mutableStateOf<android.net.Uri?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

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
                }
            }
            item {
                SettingCard("作业提醒") {
                    com.taskra.ui.components.ReminderSettings(app, vm)
                }
            }
            item {
                SettingCard("回收站") {
                    SettingAction(Icons.Outlined.DeleteOutline, "回收站", onOpenTrash)
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
                    SettingAction(Icons.Outlined.FileUpload, "导出备份") {
                        exportLauncher.launch("taskra-backup-${System.currentTimeMillis()}.zip")
                    }
                    SettingAction(Icons.Outlined.FileDownload, "恢复备份") {
                        importLauncher.launch(arrayOf("application/zip", "*/*"))
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
                        ""
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
        shape = RoundedCornerShape(5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
