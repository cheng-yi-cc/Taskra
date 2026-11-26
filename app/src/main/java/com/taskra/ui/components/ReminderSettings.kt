package com.taskra.ui.components

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.TaskraMotion
import com.taskra.ui.motion.appleContentSize
import com.taskra.ui.motion.appleTactile
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val reduced = LocalReduceMotion.current
    Column(Modifier.appleContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("作业截止提醒", Modifier.weight(1f))
            Switch(prefs.remindersEnabled, { enabled ->
                scope.launch {
                    app.prefs.setRemindersEnabled(enabled)
                    if (enabled) requestNotificationAccess()
                }
            }, Modifier.testTag("reminders_enabled").semantics { contentDescription = "作业截止提醒" })
        }
        AnimatedVisibility(
            visible = prefs.remindersEnabled,
            enter = TaskraMotion.expandEnter(reduced),
            exit = TaskraMotion.collapseExit(reduced),
        ) {
            Column(Modifier.appleContentSize()) {
                Text("提醒方式", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = prefs.reminderMode == "DAY_TIME",
                        onClick = { scope.launch { app.prefs.setReminderMode("DAY_TIME") } },
                        label = { Text("前一天指定时刻") }, modifier = Modifier.testTag("reminder_mode_day"))
                    FilterChip(selected = prefs.reminderMode == "BEFORE_DEADLINE",
                        onClick = { scope.launch { app.prefs.setReminderMode("BEFORE_DEADLINE") } },
                        label = { Text("提前指定时长") }, modifier = Modifier.testTag("reminder_mode_offset"))
                }
                AnimatedContent(
                    targetState = prefs.reminderMode == "DAY_TIME",
                    transitionSpec = { TaskraMotion.crossfadeSpec(reduced) },
                    label = "reminderModeBody",
                ) { isDayTime ->
                    if (isDayTime) {
                        val timeRowInteraction = remember { MutableInteractionSource() }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .appleTactile(timeRowInteraction, pressedScale = 0.985f, cornerRadius = 6.dp)
                                .testTag("reminder_time")
                                .clickable(interactionSource = timeRowInteraction, indication = null) { showTime = true }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("提醒时间", Modifier.weight(1f))
                            Text(
                                "%02d:%02d".format(prefs.reminderMinuteOfDay / 60, prefs.reminderMinuteOfDay % 60),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
                        }
                    } else {
                        Column {
                            listOf(listOf(1 to "1小时", 6 to "6小时", 12 to "12小时"),
                                listOf(24 to "1天", 48 to "2天", 72 to "3天", 168 to "7天")).forEach { options ->
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    options.forEach { (hours, label) ->
                                        FilterChip(selected = prefs.reminderHours == hours,
                                            onClick = { scope.launch { app.prefs.setReminderHours(hours) } },
                                            label = { Text(label) }, modifier = Modifier.testTag("reminder_hours_$hours"))
                                    }
                                }
                            }
                            Text("仅设日期时，以截止日期当天 09:00 为基准。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                AnimatedVisibility(
                    visible = !allowed,
                    enter = TaskraMotion.expandEnter(reduced),
                    exit = TaskraMotion.collapseExit(reduced),
                ) {
                    Column {
                        Text("系统通知未开启", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { requestNotificationAccess() }, modifier = Modifier.testTag("reminder_permission")) { Text("开启系统通知") }
                    }
                }
                AnimatedVisibility(
                    visible = Build.VERSION.SDK_INT >= 31 && !exact,
                    enter = TaskraMotion.expandEnter(reduced),
                    exit = TaskraMotion.collapseExit(reduced),
                ) {
                    Column {
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}")))
                        }, modifier = Modifier.testTag("reminder_exact_permission")) { Text("允许准时提醒") }
                        Text("未授权时，省电模式可能延迟提醒", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (showTime) {
        val time = rememberTimePickerState(prefs.reminderMinuteOfDay / 60, prefs.reminderMinuteOfDay % 60, true)
        var useDial by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showTime = false }, title = { Text("提醒时间") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()).appleContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(
                    targetState = useDial,
                    transitionSpec = { TaskraMotion.crossfadeSpec(reduced) },
                    label = "reminderClockMode",
                ) { dialMode ->
                    if (dialMode) TimePicker(time) else TimeInput(time)
                }
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
        text = { Text("允许系统通知后，可以收到作业截止提醒。") },
        confirmButton = { TextButton(onClick = {
            showRationale = false
            app.reminders.permissionRequested = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }) { Text("继续") } },
        dismissButton = { TextButton(onClick = { showRationale = false }) { Text("取消") } },
    )
}
