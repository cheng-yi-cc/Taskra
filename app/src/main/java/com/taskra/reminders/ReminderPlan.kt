package com.taskra.reminders

import com.taskra.data.DeadlineType
import com.taskra.data.Homework
import com.taskra.data.Status
import com.taskra.data.UserPrefs
import com.taskra.util.TimeUtils
import java.time.LocalDate
import java.time.LocalTime

data class HomeworkReminder(val homework: Homework, val key: String, val at: Long, val expiresAt: Long)

/** 指定时刻模式沿用旧偏好；提前时长模式以截止时间或截止日期当天 09:00 为基准。 */
fun reminderFor(homework: Homework, minuteOfDay: Int, mode: String = "DAY_TIME", hours: Int = 24): HomeworkReminder? {
    if (homework.status != Status.TODO || homework.deletedAt != null ||
        homework.deadlineType !in listOf(DeadlineType.DATE, DeadlineType.DATETIME)) return null
    return runCatching {
        val date = LocalDate.parse(homework.deadlineDate)
        val zone = TimeUtils.zoneOf(homework.deadlineZoneId)
        val key = "${homework.id}|$date|${zone.id}"
        if (mode == "BEFORE_DEADLINE") {
            val expires = TimeUtils.deadlineBoundaryMillis(homework.deadlineType, homework.deadlineDate,
                homework.deadlineTime, homework.deadlineZoneId) ?: return null
            val base = if (homework.deadlineType == DeadlineType.DATE) {
                date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
            } else expires
            val detail = if (homework.deadlineType == DeadlineType.DATETIME) "|${homework.deadlineTime}" else ""
            HomeworkReminder(homework, key + detail, base - hours.coerceIn(1, 168) * 3_600_000L, expires)
        } else {
            val time = LocalTime.ofSecondOfDay(minuteOfDay.coerceIn(0, 1439) * 60L)
            HomeworkReminder(homework, key,
                date.minusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli(),
                date.atStartOfDay(zone).toInstant().toEpochMilli())
        }
    }.getOrNull()
}

data class ReminderPlan(val due: List<HomeworkReminder>, val nextAt: Long?)

fun planReminders(homework: List<Homework>, prefs: UserPrefs, delivered: Set<String>, now: Long): ReminderPlan {
    if (!prefs.remindersEnabled) return ReminderPlan(emptyList(), null)
    val reminders = homework.mapNotNull { reminderFor(it, prefs.reminderMinuteOfDay, prefs.reminderMode, prefs.reminderHours) }
        .filter { now < it.expiresAt }
    val due = reminders.filter { now >= it.at && it.key !in delivered }
    // 午夜也检查一次，清除已到截止当天的通知；授权被拒绝时不循环重试当日通知。
    val next = reminders.flatMap {
        if (it.key !in delivered && it.at > now) listOf(it.at) else listOf(it.expiresAt)
    }.minOrNull()
    return ReminderPlan(due, next)
}
