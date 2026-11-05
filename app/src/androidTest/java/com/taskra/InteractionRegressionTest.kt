package com.taskra

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskra.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InteractionRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as TaskraApp
    private fun awaitTag(tag: String) = rule.waitUntil(8000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitEditorGone() = rule.waitUntil(8000) {
        rule.onAllNodesWithTag("publish_homework").fetchSemanticsNodes().isEmpty()
    }

    @Test fun publishingDraft_returnsToRemainingDraft_thenHomeWhenEmpty() {
        val sem = runBlocking { app.repo.listSemesters().first().id }
        val id = runBlocking {
            app.repo.db.draftDao().listAll().forEach { app.repo.deleteDraft(it.id) }
            app.repo.createHomework(sem, null, "导航回归", DeadlineType.NONE, null, null, null, emptyList(), emptyList())
        }
        fun draft(key: String, homework: String?, text: String) = EditorDraft(
            id = key, homeworkId = homework, semesterId = sem,
            blocksJson = encodeBlocks(listOf(BlockJson(UUID.randomUUID().toString(), BlockKind.TEXT, text))),
        )
        runBlocking {
            app.repo.saveDraft(draft("edit:$id", id, "已有作业的新内容"))
            app.repo.saveDraft(draft("new", null, "新建导航回归"))
        }
        rule.onNodeWithContentDescription("草稿箱").performClick()
        rule.onNodeWithTag("continue_draft:new").performClick()
        awaitTag("publish_homework")
        rule.onNodeWithTag("publish_homework").performClick()
        awaitEditorGone()
        rule.onNodeWithText("草稿箱").assertIsDisplayed()
        rule.onNodeWithText("编辑草稿").assertIsDisplayed()
        // 草稿卡片本身就是“继续编辑”的入口（089b252 起没有单独的按钮文案）。
        rule.onNodeWithTag("continue_draft:edit:$id").performClick()
        awaitTag("publish_homework")
        rule.onNodeWithTag("publish_homework").performClick()
        awaitEditorGone()
        awaitTag("nav_todo")
        rule.onNodeWithText("草稿箱").assertDoesNotExist()
        runBlocking {
            assertTrue(app.repo.db.draftDao().listAll().isEmpty())
            app.repo.db.homeworkDao().listActive().filter { it.id == id || it.title == "新建导航回归" }.forEach {
                app.repo.db.homeworkDao().deleteHard(it.id)
            }
        }
    }

    @Test fun attachmentsHaveNoRepeatedHint_andMetadataStaysFixed() {
        val blocks = buildList {
            add(BlockJson("empty-before", BlockKind.TEXT, ""))
            add(BlockJson("image", BlockKind.IMAGE, imageId = "qa-missing-image"))
            add(BlockJson("empty-after", BlockKind.TEXT, ""))
            add(BlockJson("audio", BlockKind.AUDIO, audioId = "qa-missing-audio", audioDurationMs = 1000))
            repeat(20) { add(BlockJson("text-$it", BlockKind.TEXT, "第 $it 段题目\n长内容滚动检查")) }
        }
        runBlocking { app.repo.saveDraft(EditorDraft("new", blocksJson = encodeBlocks(blocks))) }
        rule.onNodeWithContentDescription("新建作业").performClick()
        awaitTag("editor_metadata")
        rule.onAllNodesWithText("写下题目…", useUnmergedTree = true).assertCountEquals(0)
        val before = rule.onNodeWithTag("editor_metadata").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("editor_document").performTouchInput { swipeUp() }
        rule.onNodeWithTag("editor_section").assertIsDisplayed()
        rule.onNodeWithTag("deadline_button").assertIsDisplayed()
        assertEquals(before, rule.onNodeWithTag("editor_metadata").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithContentDescription("返回").performClick()
        awaitEditorGone()
        runBlocking { app.repo.deleteDraft("new") }
    }

    @Test fun searchAndFilterAreModal_cancelKeepsResults_completePersists() {
        val sem = runBlocking { app.repo.listSemesters().first().id }
        val title = "浮窗回归${System.currentTimeMillis()}"
        val id = runBlocking { app.repo.createHomework(sem, null, title, DeadlineType.NONE, null, null, null, emptyList(), emptyList()) }
        rule.onNodeWithContentDescription("搜索").performClick()
        awaitTag("search_dialog")
        rule.onNodeWithTag("search_field").performTextInput(title)
        rule.onNode(hasText("搜索") and hasClickAction()).performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithContentDescription("作业：$title").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("search_dialog").assertDoesNotExist()
        rule.onNodeWithContentDescription("筛选与排序").performClick()
        awaitTag("filter_dialog")
        rule.onNodeWithText("已逾期").performClick()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithContentDescription("作业：$title").assertIsDisplayed()
        rule.onNode(hasContentDescription("标记完成") and hasAnyAncestor(hasContentDescription("作业：$title"))).performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithContentDescription("作业：$title").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("撤销").assertDoesNotExist()
        runBlocking {
            assertEquals(Status.DONE, app.repo.getHomework(id)!!.status)
            app.repo.db.homeworkDao().deleteHard(id)
        }
    }
}
