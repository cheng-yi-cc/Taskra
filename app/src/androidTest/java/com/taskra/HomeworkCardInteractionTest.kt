package com.taskra

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskra.data.DeadlineType
import com.taskra.data.Homework
import com.taskra.ui.TodoUiItem
import com.taskra.ui.components.HomeworkCard
import com.taskra.ui.components.SwipeToTrash
import com.taskra.ui.theme.TaskraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 在隔离模拟器校验卡片触控、截止信息和左滑删除。 */
@RunWith(AndroidJUnit4::class)
class HomeworkCardInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val item = TodoUiItem(
        h = Homework("card-test", "term", "course", "习题摘要", deadlineType = DeadlineType.DATE, deadlineDate = "2026-12-10"),
        courseName = "机械设计", courseColor = 1, overdue = false,
        dueText = "12月10日 当天结束前截止", summary = "习题摘要：完成课后练习", imageCount = 0,
    )

    @Test fun cardAndCompletionHaveSeparateActionsAndDeadlineIsVisible() {
        var opened = 0
        var completed = 0
        rule.runOnUiThread {
            rule.activity.setContent { TaskraTheme(darkTheme = false) {
                HomeworkCard(item, onOpen = { opened++ }, onToggleDone = { completed++ })
            } }
        }
        rule.onNodeWithText("习题摘要：完成课后练习", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("机械设计", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("12月10日 当天结束前截止", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("标记完成", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(1, completed); assertEquals(0, opened) }
        rule.onNodeWithContentDescription("作业：习题摘要").performClick()
        rule.runOnIdle { assertEquals(1, opened); assertEquals(1, completed) }
    }

    @Test fun swipeDeletesOnceAndBackgroundIsHiddenAtRest() {
        var deletions = 0
        rule.runOnUiThread {
            rule.activity.setContent { TaskraTheme(darkTheme = true) {
                SwipeToTrash(onDelete = { deletions++ }, modifier = Modifier.fillMaxWidth().testTag("card_swipe")) {
                    HomeworkCard(item, onOpen = {}, onToggleDone = {})
                }
            } }
        }
        rule.onNodeWithText("删除", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("12月10日 当天结束前截止", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("card_swipe").performTouchInput { swipeLeft() }
        rule.waitUntil(5000) { deletions == 1 }
        rule.runOnIdle { assertEquals(1, deletions) }
    }
}
