package com.taskra.reminders

import com.taskra.data.DeadlineType
import com.taskra.data.Homework
import com.taskra.data.Status
import com.taskra.data.UserPrefs
import com.taskra.util.TimeUtils
import java.time.LocalDate
import java.time.LocalTime

data class HomeworkReminder(val homework: Homework, val key: String, val at: Long, val expiresAt: Long)

/** 使用用户选中的截止日期，而不是 DATE 类型的次日零点边界。 */
fun reminderFor(homework: Homework, minuteOfDay: Int): HomeworkReminder? {
    if (homework.status != Status.TODO || homework.deletedAt != null ||
        homework.deadlineType !in listOf(DeadlineType.DATE, DeadlineType.DATETIME)) return null
    return runCatching {
        val date = LocalDate.parse(homework.deadlineDate)
        val zone = TimeUtils.zoneOf(homework.deadlineZoneId)
        val time = LocalTime.ofSecondOfDay(minuteOfDay.coerceIn(0, 1439) * 60L)
        HomeworkReminder(
            homework, "${homework.id}|$date|${zone.id}",
            date.minusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli(),
            date.atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }.getOrNull()
}

data class ReminderPlan(val due: List<HomeworkReminder>, val nextAt: Long?)

fun planReminders(homework: List<Homework>, prefs: UserPrefs, delivered: Set<String>, now: Long): ReminderPlan {
    if (!prefs.remindersEnabled) return ReminderPlan(emptyList(), null)
    val reminders = homework.mapNotNull { reminderFor(it, prefs.reminderMinuteOfDay) }
        .filter { now < it.expiresAt }
    val due = reminders.filter { now >= it.at && it.key !in delivered }
    // 午夜也检查一次，清除已到截止当天的通知；授权被拒绝时不循环重试当日通知。
    val next = reminders.flatMap {
        if (it.key !in delivered && it.at > now) listOf(it.at) else listOf(it.expiresAt)
    }.minOrNull()
    return ReminderPlan(due, next)
}
