package com.taskra

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskra.data.DeadlineType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorPersistenceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test fun immediateBack_restoresDraft_dateOnlyPublishes_withoutDraftResurrection() {
        val app = rule.activity.application as TaskraApp
        runBlocking { app.repo.deleteDraft("new") }
        val text = "草稿回归${System.currentTimeMillis()}"
        rule.onNodeWithContentDescription("新建作业").performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("question_text").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("editor_title").assertDoesNotExist()
        rule.onNodeWithTag("draft_feedback").assertHasNoClickAction()
        rule.onAllNodesWithTag("question_text")[0].performTextInput(text)
        // 不等待防抖，立即离开，验证最后一次输入也被提交。
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("question_text").fetchSemanticsNodes().isEmpty() }
        assertNotNull(runBlocking { app.repo.getDraft("new") })
        rule.onNodeWithContentDescription("新建作业").performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("question_text").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("question_text")[0].assertTextContains(text)
        rule.onAllNodesWithTag("question_text")[0].performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithContentDescription("文字格式").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("文字格式").performClick()
        rule.onNodeWithContentDescription("字号").performClick()
        rule.onNodeWithText("大号").performClick()
        rule.onNodeWithContentDescription("加粗").assertIsDisplayed()
        rule.onAllNodesWithTag("question_text")[0].performTextInputSelection(androidx.compose.ui.text.TextRange(0, text.length))
        rule.onNodeWithContentDescription("加粗").performClick()
        rule.onNodeWithContentDescription("背景高亮").performClick()
        rule.onNodeWithTag("deadline_button").performClick()
        rule.onNodeWithContentDescription("取消截止时间设置").performClick()
        assertEquals(DeadlineType.NONE, runBlocking { app.repo.getDraft("new") }!!.deadlineType)
        rule.onNodeWithTag("deadline_button").performClick()
        rule.onNodeWithTag("deadline_confirm").performClick()
        rule.onNodeWithContentDescription("保存").performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("publish_homework").fetchSemanticsNodes().isEmpty() }
        rule.mainClock.advanceTimeBy(1000)
        runBlocking {
            val saved = app.db.homeworkDao().listActive().single { it.title == text }
            assertEquals(DeadlineType.DATE, saved.deadlineType)
            assertNotNull(saved.deadlineDate)
            assertNull(saved.deadlineTime)
            val spans = com.taskra.util.decodeSpans(app.repo.detail(saved.id)!!.question.first().spanJson)
            assertTrue(spans.any { it.type == com.taskra.util.SpanType.BOLD })
            assertTrue(spans.any { it.type == com.taskra.util.SpanType.HIGHLIGHT })
            assertTrue(spans.any { it.type == com.taskra.util.SpanType.HEADING && it.level == 2 })
            assertNull(app.repo.getDraft("new"))
            app.db.homeworkDao().deleteHard(saved.id)
        }
    }
}
