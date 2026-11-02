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
class SwipeAndAttachmentTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as TaskraApp
    private fun awaitTag(tag: String) = rule.waitUntil(8000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun swipeHomeworkDoesNotComplete_andCourseTrashCanRestoreAll() {
        val courseId = UUID.randomUUID().toString()
        val name = "左滑课程${System.currentTimeMillis()}"
        val ids = runBlocking {
            val sem = app.repo.listSemesters().first().id
            app.repo.saveCourse(Course(courseId, sem, name))
            val todo = app.repo.createHomework(sem, courseId, "左滑待办", "NONE", null, null, null, emptyList(), emptyList())
            val done = app.repo.createHomework(sem, courseId, "完成的作业", "NONE", null, null, null, emptyList(), emptyList())
            app.repo.markDone(done)
            todo to done
        }
        rule.onNodeWithTag("todo_list").performScrollToNode(hasTestTag("swipe_homework:${ids.first}"))
        rule.onNodeWithTag("swipe_homework:${ids.first}").performTouchInput { swipeLeft() }
        rule.waitUntil(8000) { runBlocking { app.repo.getHomework(ids.first)!!.deletedAt != null } }
        assertEquals(Status.TODO, runBlocking { app.repo.getHomework(ids.first)!!.status })
        rule.waitUntil(8000) { rule.onAllNodesWithTag("swipe_homework:${ids.first}").fetchSemanticsNodes().isEmpty() }
        runBlocking { app.repo.restoreFromTrash(ids.first) }
        rule.onNodeWithTag("nav_courses").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("swipe_course:$courseId"))
        awaitTag("swipe_course:$courseId")
        rule.onNodeWithText(name).performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithContentDescription("作业：左滑待办").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("作业：左滑待办").assertExists()
        rule.onNodeWithContentDescription("作业：完成的作业").assertExists()
        rule.onNodeWithText("开始复习").assertDoesNotExist()
        rule.onNodeWithText("从早到晚").assertDoesNotExist()
        rule.onNodeWithContentDescription("筛选课程作业").performClick()
        rule.onNodeWithText("未完成").performClick()
        rule.onNodeWithText("应用").performClick()
        rule.onNodeWithContentDescription("作业：完成的作业").assertDoesNotExist()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithTag("swipe_course:$courseId").performTouchInput { swipeLeft() }
        rule.waitUntil(8000) { runBlocking { app.repo.getCourse(courseId)!!.deletedAt != null } }
        runBlocking {
            assertNotNull(app.repo.getHomework(ids.first)!!.deletedAt)
            assertNotNull(app.repo.getHomework(ids.second)!!.deletedAt)
        }
        rule.onNodeWithTag("nav_settings").performClick()
        rule.onNode(hasText("回收站") and hasClickAction()).performClick()
        rule.onNodeWithText(name).assertExists()
        rule.onNodeWithText("恢复课程").performClick()
        rule.waitUntil(8000) { runBlocking { app.repo.getCourse(courseId)!!.deletedAt == null } }
        runBlocking {
            assertNull(app.repo.getHomework(ids.first)!!.deletedAt)
            assertNull(app.repo.getHomework(ids.second)!!.deletedAt)
            assertEquals(Status.DONE, app.repo.getHomework(ids.second)!!.status)
            app.db.homeworkDao().deleteHard(ids.first)
            app.db.homeworkDao().deleteHard(ids.second)
            app.db.courseDao().deleteHard(courseId)
        }
    }

    @Test fun publishedPhoto_canAddText_andRemovingPhotoLeavesOneTextBlock() {
        val id = runBlocking {
            app.repo.createHomework(app.repo.listSemesters().first().id, null, "纯图片编辑回归", "NONE", null, null, null,
                listOf(ContentBlock("photo-${UUID.randomUUID()}", "", kind = BlockKind.IMAGE, imageId = "qa-photo")), emptyList())
        }
        rule.onNodeWithTag("todo_list").performScrollToNode(hasContentDescription("作业：纯图片编辑回归"))
        rule.onNodeWithContentDescription("作业：纯图片编辑回归").performClick()
        rule.onNodeWithContentDescription("编辑").performClick()
        awaitTag("question_text")
        rule.onNodeWithTag("editor_document").performScrollToIndex(2)
        val fields = rule.onAllNodesWithTag("question_text")
        fields[fields.fetchSemanticsNodes().lastIndex].performTextInput("补充文字")
        rule.onNodeWithTag("publish_homework").performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("publish_homework").fetchSemanticsNodes().isEmpty() }
        runBlocking {
            val q = app.repo.detail(id)!!.question
            assertTrue(q.any { it.kind == BlockKind.IMAGE })
            assertTrue(q.any { it.text == "补充文字" })
        }
        rule.onNodeWithContentDescription("编辑").performClick()
        awaitTag("question_text")
        rule.onNodeWithContentDescription("删除这张图片").performClick()
        rule.onAllNodesWithTag("question_text").assertCountEquals(1)
        rule.onNodeWithTag("question_text").assertTextContains("补充文字")
        rule.onNodeWithTag("publish_homework").performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("publish_homework").fetchSemanticsNodes().isEmpty() }
        runBlocking {
            assertEquals(1, app.repo.detail(id)!!.question.size)
            app.db.homeworkDao().deleteHard(id)
        }
    }
}
