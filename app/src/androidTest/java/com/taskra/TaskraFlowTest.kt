package com.taskra

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 关键交互端到端（模拟器/真机）：
 * 新建课程 → 新建作业（含文字块）→ 待办出现 → 标记完成 → 待办消失 →
 * 课程档案保留 → 重新练习一轮并标记 → 进度统计正确。
 *
 * 注意：跟随数据库挂起操作的导航经主线程投递（见 postOnMain），
 * 因此断言统一使用 waitUntil 等待节点出现，而非假设时序。
 */
@RunWith(AndroidJUnit4::class)
class TaskraFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val ts = System.currentTimeMillis() % 100000
    private val courseName = "E2E课程$ts"
    private val hwTitle = "E2E作业$ts"

    private fun waitForText(text: String, timeout: Long = 8000) {
        rule.waitUntil(timeout) {
            rule.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun contentDescExists(label: String): Boolean {
        return try {
            rule.onNodeWithContentDescription(label, useUnmergedTree = true).assertExists()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 懒加载列表：卡片可能在首屏之下尚未组合，先滑动再断言。
     * （待办页筛选区较高，小屏首屏可能只露出列表头部。）
     */
    private fun scrollUntilCard(listTag: String, label: String) {
        repeat(8) {
            if (contentDescExists(label)) return
            try {
                rule.onNodeWithTag(listTag).performTouchInput { swipeUp() }
            } catch (_: Throwable) {
            }
            rule.mainClock.advanceTimeBy(400)
        }
        rule.onNodeWithContentDescription(label, useUnmergedTree = true).assertExists()
    }

    private fun dumpTexts(tag: String) {
        try {
            val onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            println("DIAG[$tag] testThreadIsMain=$onMain")
        } catch (e: Exception) {
            println("DIAG[$tag] thread probe failed: $e")
        }
        try {
            val app = rule.activity.application as TaskraApp
            val all = kotlinx.coroutines.runBlocking {
                app.db.homeworkDao().listActive() + app.db.homeworkDao().listTrash()
            }
            println("DIAG[$tag] DB total=${all.size} " + all.take(20).joinToString("; ") {
                "${it.title}|${it.status}|del=${it.deletedAt != null}"
            })
            val mine = all.firstOrNull { it.title.startsWith("E2E作业") }
            if (mine != null) {
                val bl = kotlinx.coroutines.runBlocking {
                    app.db.blockDao().listByHomework(mine.id)
                }
                println("DIAG[$tag] BLOCKS n=${bl.size} " + bl.take(10).joinToString("; ") {
                    "${it.region}/${it.kind}/${it.text?.take(24)}/${it.imageId?.take(8)}"
                })
            }
        } catch (e: Exception) {
            println("DIAG[$tag] db probe failed: $e")
        }
        try {
            val nodes = rule.onAllNodesWithText("", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
            println("DIAG[$tag] nodes=${nodes.size}")
            nodes.take(100).forEach { n ->
                val props = n.config.joinToString(" | ") { e -> "${e.key.name}=${e.value.toString().take(80)}" }
                println("DIAG[$tag] $props")
            }
        } catch (e: Exception) {
            println("DIAG[$tag] dump failed: $e")
        }
    }

    @Test
    fun fullFlow_create_complete_review() {
        // 0. 自励区存在（默认示例或用户文字）
        rule.onNodeWithContentDescription("编辑给自己的话").assertIsDisplayed()

        // 1. 课程页新建课程
        rule.onNodeWithTag("nav_courses").performClick()
        rule.onNodeWithContentDescription("新建课程").performClick()
        rule.onNodeWithTag("course_name_field").performTextInput(courseName)
        rule.onNodeWithText("创建").performClick()
        // 等新建对话框关闭（标题“新建课程”消失），避免匹配到框内输入文字
        rule.waitUntil(8000) {
            rule.onAllNodesWithText("新建课程").fetchSemanticsNodes().isEmpty()
        }
        waitForText(courseName)
        rule.onNodeWithText(courseName).assertIsDisplayed()

        // 2. 进入课程，新建作业（自动带入课程+学期）
        rule.onNodeWithText(courseName).performClick()
        waitForText("重新练习本课程作业")
        rule.onNodeWithContentDescription("在此课程新建作业").performClick()
        rule.onNodeWithTag("editor_title").performTextInput(hwTitle)
        // 题目文字块：第一个题目输入框
        rule.onAllNodesWithTag("question_text")[0].performTextInput("第一段题目要求")
        // 选课程：已自动带入，确认显示课程名即可
        rule.onNodeWithText("课程：$courseName", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("保存").performClick()
        // 保存后经主线程返回课程详情
        waitForText("重新练习本课程作业")
        // 课程详情页无底部导航，先返回课程列表
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitUntil(8000) {
            rule.onAllNodesWithTag("nav_todo").fetchSemanticsNodes().isNotEmpty()
        }

        // 3. 回到待办（底部导航），作业出现
        // 注：作业卡片带 contentDescription + clickable，文本节点被合并，
        // 因此用 contentDescription 定位卡片（已用截图验证卡片实际渲染正常）。
        rule.onNodeWithTag("nav_todo").performClick()
        scrollUntilCard("todo_list", "作业：$hwTitle")

        // 4. 标记完成 → 待办消失
        rule.onNodeWithContentDescription("标记完成").performClick()
        rule.waitUntil(8000) { !contentDescExists("作业：$hwTitle") }

        // 5. 课程档案默认“已完成”保留该作业
        rule.onNodeWithTag("nav_courses").performClick()
        waitForText(courseName)
        rule.onNodeWithText(courseName).performClick()
        scrollUntilCard("course_list", "作业：$hwTitle")

        // 6. 重新练习：只看题目，标记“这次会做了”，进度 1/1
        rule.onNodeWithText("重新练习本课程作业").performClick()
        dumpTexts("after-open-review")
        waitForText("第一段题目要求")
        rule.onNodeWithText("第一段题目要求", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("这次会做了").performClick()
        waitForText("本轮已练1/1项，其中0项还需要再练。")
        rule.onNodeWithText("本轮已练1/1项，其中0项还需要再练。").assertIsDisplayed()
    }

    @Test
    fun motto_edit_save() {
        val text = "E2E自励$ts"
        rule.onNodeWithContentDescription("编辑给自己的话").performClick()
        rule.onNodeWithTag("motto_field").performTextClearance()
        rule.onNodeWithTag("motto_field").performTextInput(text)
        rule.onNodeWithText("保存").performClick()
        waitForText(text)
        rule.onNodeWithText(text).assertIsDisplayed()
    }
}
