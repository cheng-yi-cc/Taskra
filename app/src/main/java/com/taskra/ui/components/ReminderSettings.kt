package com.taskra.ui.components

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.taskra.TaskraApp
import com.taskra.ui.AppViewModel
import kotlinx.coroutines.launch

/** 有需要提醒的作业时只主动询问一次；拒绝后可在设置中主动重新授权。 */
@Composable
fun ReminderPermissionPrompt(app: TaskraApp, enabled: Boolean, hasDeadline: Boolean) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        app.reminders.refresh()
    }
    LaunchedEffect(enabled, hasDeadline) {
        if (enabled && hasDeadline && Build.VERSION.SDK_INT >= 33 &&
            !app.reminders.notificationsAllowed() && !app.reminders.permissionRequested &&
            app.prefs.snapshot().remindersEnabled) {
            app.reminders.permissionRequested = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSettings(app: TaskraApp, vm: AppViewModel) {
    val prefs by vm.prefs.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(app.reminders.notificationsAllowed()) }
    var exact by remember { mutableStateOf(app.reminders.exactAlarmsAllowed()) }
    var showTime by remember { mutableStateOf(false) }
    var showRationale by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = app.reminders.notificationsAllowed()
        app.reminders.refresh()
    }
    fun openNotificationSettings() {
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, com.taskra.reminders.HomeworkReminders.CHANNEL))
    }
    fun requestNotificationAccess() {
        if (app.reminders.notificationsAllowed()) return
        val activity = context as? Activity
        if (Build.VERSION.SDK_INT >= 33) {
            when {
                activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true -> showRationale = true
                !app.reminders.permissionRequested -> {
                    app.reminders.permissionRequested = true
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> openNotificationSettings()
            }
        } else openNotificationSettings()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = app.reminders.notificationsAllowed()
                exact = app.reminders.exactAlarmsAllowed()
                app.reminders.refresh()
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("截止前一天提醒", Modifier.weight(1f))
            Switch(prefs.remindersEnabled, { enabled ->
                scope.launch {
                    app.prefs.setRemindersEnabled(enabled)
                    if (enabled) requestNotificationAccess()
                }
            }, Modifier.testTag("reminders_enabled").semantics { contentDescription = "截止前一天提醒" })
        }
        if (prefs.remindersEnabled) {
            Row(Modifier.fillMaxWidth().testTag("reminder_time").clickable { showTime = true }.padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("提醒时间", Modifier.weight(1f))
                Text("%02d:%02d".format(prefs.reminderMinuteOfDay / 60, prefs.reminderMinuteOfDay % 60),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
            }
            if (!allowed) {
                Text("系统通知未开启", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { requestNotificationAccess() }, modifier = Modifier.testTag("reminder_permission")) { Text("开启系统通知") }
            }
            if (Build.VERSION.SDK_INT >= 31 && !exact) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}")))
                }, modifier = Modifier.testTag("reminder_exact_permission")) { Text("允许准时提醒") }
                Text("未授权时，省电模式可能延迟提醒", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showTime) {
        val time = rememberTimePickerState(prefs.reminderMinuteOfDay / 60, prefs.reminderMinuteOfDay % 60, true)
        var useDial by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showTime = false }, title = { Text("提醒时间") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                if (useDial) TimePicker(time) else TimeInput(time)
                TextButton(onClick = { useDial = !useDial }) { Text(if (useDial) "数字输入" else "表盘选择") }
            } },
            confirmButton = { TextButton(modifier = Modifier.testTag("reminder_time_confirm"), onClick = {
                vm.setReminderTime(time.hour * 60 + time.minute)
                showTime = false
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("取消") } },
        )
    }
    if (showRationale) AlertDialog(
        onDismissRequest = { showRationale = false }, title = { Text("开启作业提醒") },
        text = { Text("允许系统通知后，可以收到截止前一天的作业提醒。") },
        confirmButton = { TextButton(onClick = {
            showRationale = false
            app.reminders.permissionRequested = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }) { Text("继续") } },
        dismissButton = { TextButton(onClick = { showRationale = false }) { Text("取消") } },
    )
}
