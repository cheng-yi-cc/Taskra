package com.taskra

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskra.data.*
import com.taskra.reminders.HomeworkReminders
import com.taskra.ui.AppViewModel
import com.taskra.ui.components.ReminderSettings
import com.taskra.ui.theme.TaskraTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 仅在模拟器运行；夹具使用独立 ID，保留既有记录与附件。 */
@RunWith(AndroidJUnit4::class)
class ReminderFeatureTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as TaskraApp
    private val prefix = "reminderqa-${UUID.randomUUID()}"
    private val semId = "$prefix-sem"
    private val courseId = "$prefix-course"
    private val ids = mutableListOf<String>()
    private lateinit var original: UserPrefs
    private var permissionRequested = false
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private fun active(id: String) = manager.activeNotifications.firstOrNull { it.tag == id }
    private fun refresh() = runBlocking { app.reminders.refreshSafely() }

    @Before fun prepare() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "本测试仅允许模拟器" }
        original = runBlocking { app.prefs.snapshot() }
        permissionRequested = app.reminders.permissionRequested
        app.reminders.permissionRequested = true
        runBlocking {
            app.prefs.setRemindersEnabled(false)
            app.repo.saveSemester(Semester(semId, prefix))
            app.repo.saveCourse(Course(courseId, semId, prefix))
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.POST_NOTIFICATIONS")
        refresh()
    }

    @After fun cleanup() = runBlocking {
        ids.forEach { app.db.homeworkDao().deleteHard(it) }
        app.db.courseDao().deleteHard(courseId)
        app.db.semesterDao().delete(semId)
        app.prefs.restore(original)
        app.reminders.permissionRequested = permissionRequested
        app.reminders.refreshSafely()
    }

    private fun create(days: Long = 1): String = runBlocking {
        app.repo.createHomework(semId, courseId, prefix, DeadlineType.DATE,
            LocalDate.now().plusDays(days).toString(), null, ZoneId.systemDefault().id,
            listOf(ContentBlock("$prefix-block-${ids.size}", "", text = "提醒不能改变正文")), emptyList()
        ).also { ids += it }
    }

    @Test fun dueNotificationDeduplicatesAndClickOpensHomework() {
        val id = create()
        runBlocking { app.prefs.setReminderTime(0); app.prefs.setRemindersEnabled(true) }
        refresh()
        val notification = active(id)!!
        assertEquals(prefix, notification.notification.extras.getString("android.title"))
        assertTrue(notification.notification.extras.getString("android.text")!!.contains("明天截止"))
        manager.cancel(id, notification.id)
        refresh()
        assertNull("用户划掉后不应重复通知", active(id))
        notification.notification.contentIntent.send()
        rule.waitUntil(10000) { rule.onAllNodesWithText("提醒不能改变正文").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("提醒不能改变正文").assertIsDisplayed()
        assertEquals(1, runBlocking { app.repo.detail(id)!!.question.size })
    }

    @Test fun completionTrashCourseAndSwitchCancelNotifications() {
        runBlocking { app.prefs.setReminderTime(0); app.prefs.setRemindersEnabled(true) }
        val completed = create(); refresh(); assertNotNull(active(completed))
        runBlocking { app.repo.markDone(completed) }; refresh(); assertNull(active(completed))
        val deleted = create(); refresh(); assertNotNull(active(deleted))
        runBlocking { app.repo.moveToTrash(deleted) }; refresh(); assertNull(active(deleted))
        val courseWork = create(); refresh(); assertNotNull(active(courseWork))
        runBlocking { app.repo.moveCourseToTrash(courseId) }; refresh(); assertNull(active(courseWork))
        runBlocking { app.repo.restoreCourse(courseId) }; refresh(); assertNull("已提醒的恢复作业不重复响铃", active(courseWork))
        val switched = create(); refresh(); assertNotNull(active(switched))
        runBlocking { app.prefs.setRemindersEnabled(false) }; refresh(); assertNull(active(switched))
        assertEquals("提醒不能改变正文", runBlocking { app.repo.detail(switched)!!.question.single().text })
    }

    @Test fun deadlineEditAndSettingsTimeCancelPreserveAndSave() {
        val id = create()
        runBlocking { app.prefs.setReminderTime(0); app.prefs.setRemindersEnabled(true) }
        refresh(); assertNotNull(active(id))
        runBlocking {
            val detail = app.repo.detail(id)!!
            app.repo.saveHomeworkWithBlocks(detail.homework.copy(deadlineDate = LocalDate.now().plusDays(2).toString()), detail.question, detail.solution)
        }
        refresh(); assertNull(active(id))
        runBlocking { app.prefs.setReminderTime(540) }
        rule.runOnUiThread {
            val vm = ViewModelProvider(rule.activity, AppViewModel.Factory(app))[AppViewModel::class.java]
            rule.activity.setContent { TaskraTheme { ReminderSettings(app, vm) } }
        }
        rule.waitUntil(10000) { rule.onAllNodesWithText("09:00").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("reminder_time").performClick()
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("10")
        fields[1].performTextReplacement("30")
        rule.onNodeWithText("取消").performClick()
        assertEquals(540, runBlocking { app.prefs.snapshot().reminderMinuteOfDay })
        rule.onNodeWithTag("reminder_time").performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("10")
        rule.onAllNodes(hasSetTextAction())[1].performTextReplacement("30")
        rule.onNodeWithTag("reminder_time_confirm").performClick()
        rule.waitUntil(10000) { runBlocking { app.prefs.snapshot().reminderMinuteOfDay == 630 } }
        rule.onNodeWithTag("reminders_enabled").performClick()
        rule.waitUntil(10000) { !runBlocking { app.prefs.snapshot().remindersEnabled } }
        rule.onNodeWithTag("reminder_time").assertDoesNotExist()
    }

    @Test fun realAlarmDeliversWhileAppInBackground() {
        assertTrue("模拟器需先授权准时提醒", app.reminders.exactAlarmsAllowed())
        val next = java.time.LocalDateTime.now().plusMinutes(1).withSecond(0).withNano(0)
        val id = runBlocking {
            app.repo.createHomework(semId, courseId, prefix, DeadlineType.DATE,
                next.toLocalDate().plusDays(1).toString(), null, ZoneId.systemDefault().id,
                emptyList(), emptyList()).also { ids += it }
        }
        runBlocking {
            app.prefs.setReminderTime(next.hour * 60 + next.minute)
            app.prefs.setRemindersEnabled(true)
        }
        refresh()
        assertNull(active(id))
        rule.runOnUiThread { rule.activity.moveTaskToBack(true) }
        rule.waitUntil(75000) { active(id) != null }
        assertEquals(prefix, active(id)!!.notification.extras.getString("android.title"))
    }
}
