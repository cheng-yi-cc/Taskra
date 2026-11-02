package com.taskra.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.taskra.MainActivity
import com.taskra.R
import com.taskra.TaskraApp
import com.taskra.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HomeworkReminders(private val app: TaskraApp) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val state = app.getSharedPreferences("reminder_delivery", Context.MODE_PRIVATE)
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private var started = false

    var permissionRequested: Boolean
        get() = state.getBoolean("permission_requested", false)
        set(value) { state.edit().putBoolean("permission_requested", value).apply() }

    fun notificationsAllowed(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun exactAlarmsAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun start() {
        if (started) return
        started = true
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "作业截止提醒", NotificationManager.IMPORTANCE_DEFAULT))
        scope.launch {
            combine(app.repo.observeTodo(), app.prefs.prefs) { _, _ -> Unit }.collect { refreshSafely() }
        }
    }

    fun refresh() { scope.launch { refreshSafely() } }

    suspend fun refreshSafely() {
        try { reconcile() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Log.e("HomeworkReminders", "更新提醒失败", e) }
    }

    private suspend fun reconcile() = mutex.withLock {
        val prefs = app.prefs.snapshot()
        val now = System.currentTimeMillis()
        val homework = app.db.homeworkDao().listActive()
        val delivered = runCatching { Json.decodeFromString<Map<String, Long>>(state.getString("delivered", "{}")!!) }
            .getOrDefault(emptyMap()).filterValues { it > now }.toMutableMap()
        val valid = if (prefs.remindersEnabled) homework.mapNotNull { reminderFor(it, prefs.reminderMinuteOfDay, prefs.reminderMode, prefs.reminderHours) }
            .filter { now >= it.at && now < it.expiresAt }.associate { it.homework.id to it.key } else emptyMap()
        notifications.activeNotifications.filter { it.notification.channelId == CHANNEL &&
            (it.tag !in valid || it.notification.extras.getString("reminder_key") != valid[it.tag]) }
            .forEach { notifications.cancel(it.tag, it.id) }
        val plan = planReminders(homework, prefs, delivered.keys, now)
        if (notificationsAllowed()) {
            for (reminder in plan.due) {
                // 发送前再次核对，避免旧闹钟为已完成、已删除或刚改期限的作业发通知。
                val latestPrefs = app.prefs.snapshot()
                val latest = app.repo.getHomework(reminder.homework.id)?.let { reminderFor(it, latestPrefs.reminderMinuteOfDay, latestPrefs.reminderMode, latestPrefs.reminderHours) }
                val sendNow = System.currentTimeMillis()
                if (!latestPrefs.remindersEnabled || latest == null || latest.key != reminder.key ||
                    sendNow < latest.at || sendNow >= latest.expiresAt) continue
                if (post(latest)) {
                    delivered[latest.key] = latest.expiresAt
                    // 每份作业成功发送后立即持久化去重，进程重建不会重复响铃。
                    state.edit().putString("delivered", Json.encodeToString(delivered)).commit()
                }
            }
        }
        state.edit().putString("delivered", Json.encodeToString(delivered)).commit()
        schedule(planReminders(homework, prefs, delivered.keys, System.currentTimeMillis()).nextAt)
    }

    private fun post(reminder: HomeworkReminder): Boolean {
        val h = reminder.homework
        val intent = Intent(app, MainActivity::class.java)
            .setData(Uri.parse("taskra://homework/${Uri.encode(h.id)}"))
            .putExtra(EXTRA_HOMEWORK_ID, h.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val content = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val deadline = TimeUtils.formatDeadline(h.deadlineType, h.deadlineDate, h.deadlineTime, h.deadlineZoneId)
        val text = "即将截止 · $deadline"
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_reminder)
            .setColor(0xFFB94335.toInt())
            .setContentTitle(h.title.ifBlank { "作业即将截止" })
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addExtras(android.os.Bundle().apply { putString("reminder_key", reminder.key) })
            .setCategory(NotificationCompat.CATEGORY_REMINDER).build()
        return try {
            if (!notificationsAllowed()) false else {
                notifications.notify(h.id, NOTIFICATION_ID, notification)
                true
            }
        } catch (_: SecurityException) { false }
    }

    private fun schedule(at: Long?) {
        val intent = Intent(app, ReminderReceiver::class.java).setAction(ACTION_ALARM)
        val pending = PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (at == null) { alarms.cancel(pending); return }
        try {
            if (exactAlarmsAllowed()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    companion object {
        const val CHANNEL = "homework_deadlines"
        const val EXTRA_HOMEWORK_ID = "reminder_homework_id"
        const val ACTION_ALARM = "com.taskra.REMIND_HOMEWORK"
        private const val NOTIFICATION_ID = 1
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(HomeworkReminders.ACTION_ALARM, Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        val reminders = (context.applicationContext as TaskraApp).reminders
        reminders.scope.launch {
            try { reminders.refreshSafely() } finally { pending.finish() }
        }
    }
}
