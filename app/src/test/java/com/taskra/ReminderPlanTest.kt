package com.taskra

import com.taskra.data.DeadlineType
import com.taskra.data.Homework
import com.taskra.data.Status
import com.taskra.data.UserPrefs
import com.taskra.reminders.planReminders
import com.taskra.reminders.reminderFor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class ReminderPlanTest {
    private fun millis(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()
    private val h = Homework("h", "s", null, "数学作业", deadlineType = DeadlineType.DATE,
        deadlineDate = "2026-10-01", deadlineZoneId = "Asia/Shanghai")
    private val prefs = UserPrefs()

    @Test fun `仅日期和具体时间均在所选截止日期的前一天提醒`() {
        val date = reminderFor(h, 540)!!
        assertEquals(millis("2026-09-30T09:00:00+08:00"), date.at)
        assertEquals(millis("2026-10-01T00:00:00+08:00"), date.expiresAt)
        val timed = reminderFor(h.copy(deadlineType = DeadlineType.DATETIME, deadlineTime = "07:00"), 540)!!
        assertEquals(date.at, timed.at)
    }

    @Test fun `自定义时间与月年边界正确`() {
        val reminder = reminderFor(h.copy(deadlineDate = "2027-01-01"), 23 * 60 + 59)!!
        assertEquals(millis("2026-12-31T23:59:00+08:00"), reminder.at)
    }

    @Test fun `夏令时按自然日而非减24小时`() {
        val reminder = reminderFor(h.copy(deadlineDate = "2026-03-09", deadlineZoneId = "America/New_York"), 540)!!
        assertEquals(millis("2026-03-08T09:00:00-04:00"), reminder.at)
    }

    @Test fun `关闭完成回收和无期限均不提醒`() {
        val now = millis("2026-09-30T10:00:00+08:00")
        assertEquals(0, planReminders(listOf(h), prefs.copy(remindersEnabled = false), emptySet(), now).due.size)
        assertNull(planReminders(listOf(h), prefs.copy(remindersEnabled = false), emptySet(), now).nextAt)
        listOf(h.copy(status = Status.DONE), h.copy(deletedAt = 1), h.copy(deadlineType = DeadlineType.NONE),
            h.copy(deadlineDate = "bad"), h.copy(deadlineDate = null)).forEach { assertNull(reminderFor(it, 540)) }
    }

    @Test fun `未来安排当天补发截止当天不补发`() {
        val before = planReminders(listOf(h), prefs, emptySet(), millis("2026-09-30T08:59:00+08:00"))
        assertTrue(before.due.isEmpty())
        assertEquals(millis("2026-09-30T09:00:00+08:00"), before.nextAt)
        assertEquals(1, planReminders(listOf(h), prefs, emptySet(), millis("2026-09-30T09:00:00+08:00")).due.size)
        assertEquals(1, planReminders(listOf(h), prefs, emptySet(), millis("2026-09-30T23:59:00+08:00")).due.size)
        val expired = planReminders(listOf(h), prefs, emptySet(), millis("2026-10-01T00:00:00+08:00"))
        assertTrue(expired.due.isEmpty())
        assertNull(expired.nextAt)
    }

    @Test fun `同一期限不重复时间变更不重复新期限可重新提醒`() {
        val key = reminderFor(h, 540)!!.key
        val now = millis("2026-09-30T19:00:00+08:00")
        assertTrue(planReminders(listOf(h), prefs, setOf(key), now).due.isEmpty())
        assertTrue(planReminders(listOf(h), prefs.copy(reminderMinuteOfDay = 18 * 60), setOf(key), now).due.isEmpty())
        val changed = h.copy(deadlineDate = "2026-10-02")
        assertEquals(1, planReminders(listOf(changed), prefs, setOf(key), millis("2026-10-01T10:00:00+08:00")).due.size)
    }

    @Test fun `多份作业只安排最早待触发时间已发送只等午夜清理`() {
        val future = h.copy(id = "later", deadlineDate = "2026-10-02")
        val now = millis("2026-09-30T10:00:00+08:00")
        val plan = planReminders(listOf(h, future), prefs, setOf(reminderFor(h, 540)!!.key), now)
        assertTrue(plan.due.isEmpty())
        assertEquals(millis("2026-10-01T00:00:00+08:00"), plan.nextAt)
    }

    @Test fun `提醒设置备份往返及旧备份默认值`() {
        val custom = prefs.copy(remindersEnabled = false, reminderMinuteOfDay = 1234)
        assertEquals(custom, Json.decodeFromString<UserPrefs>(Json.encodeToString(custom)))
        val old = Json.decodeFromString<UserPrefs>("""{"mottoText":"旧数据"}""")
        assertTrue(old.remindersEnabled)
        assertEquals(540, old.reminderMinuteOfDay)
    }
    @Test fun `提前时长模式依截止时刻计算并在截止前补发`() {
        val timed = h.copy(deadlineType = DeadlineType.DATETIME, deadlineTime = "18:00")
        val reminder = reminderFor(timed, 540, "BEFORE_DEADLINE", 6)!!
        assertEquals(millis("2026-10-01T12:00:00+08:00"), reminder.at)
        assertEquals(millis("2026-10-01T18:00:00+08:00"), reminder.expiresAt)
        val selected = prefs.copy(reminderMode = "BEFORE_DEADLINE", reminderHours = 6)
        assertEquals(1, planReminders(listOf(timed), selected, emptySet(), millis("2026-10-01T13:00:00+08:00")).due.size)
        assertTrue(planReminders(listOf(timed), selected, emptySet(), millis("2026-10-01T18:00:00+08:00")).due.isEmpty())
        assertNotEquals(reminder.key, reminderFor(timed.copy(deadlineTime = "19:00"), 540, "BEFORE_DEADLINE", 6)!!.key)
    }

    @Test fun `仅日期提前一天在前一天九点并保留设置`() {
        val custom = prefs.copy(reminderMode = "BEFORE_DEADLINE", reminderHours = 24)
        val reminder = reminderFor(h, 540, custom.reminderMode, custom.reminderHours)!!
        assertEquals(millis("2026-09-30T09:00:00+08:00"), reminder.at)
        assertEquals(millis("2026-10-02T00:00:00+08:00"), reminder.expiresAt)
        assertEquals(custom, Json.decodeFromString<UserPrefs>(Json.encodeToString(custom)))
        assertEquals("DAY_TIME", Json.decodeFromString<UserPrefs>("{}").reminderMode)
    }

}
