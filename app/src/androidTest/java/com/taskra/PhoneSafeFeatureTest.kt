package com.taskra

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskra.data.*
import com.taskra.ui.AppViewModel
import com.taskra.ui.screens.*
import com.taskra.ui.theme.TaskraTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 可在有真实数据的手机上运行：只删除本次随机前缀的夹具，禁止清库/卸载。 */
@RunWith(AndroidJUnit4::class)
class PhoneSafeFeatureTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as TaskraApp
    private val prefix = "phoneqa-${UUID.randomUUID()}"
    private val courseIds = mutableListOf<String>()
    private val homeworkIds = mutableListOf<String>()
    private val imageIds = mutableListOf<String>()
    private val audioIds = mutableListOf<String>()
    private lateinit var prefs: UserPrefs
    private lateinit var vm: AppViewModel
    private var oldClip: ClipData? = null
    private var touchedClipboard = false
    private fun await(tag: String) = rule.waitUntil(10000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent { TaskraTheme(darkTheme = false, content = content) } }
        rule.waitForIdle()
    }
    @Before fun prepare() {
        prefs = runBlocking { app.prefs.snapshot() }
        rule.runOnUiThread { vm = ViewModelProvider(rule.activity, AppViewModel.Factory(app))["$prefix-vm", AppViewModel::class.java] }
    }
    @After fun cleanup() {
        show { Text("验证完成") }
        rule.runOnUiThread {
            if (touchedClipboard) {
                val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                if (oldClip != null) clipboard.setPrimaryClip(oldClip!!) else clipboard.clearPrimaryClip()
            }
        }
        runBlocking {
            // 新建作业可能由 UI 发布，只按本测试课程及随机 ID 收集。
            courseIds.forEach { cid -> homeworkIds += app.db.homeworkDao().listAllByCourse(cid).map { it.id } }
            app.db.draftDao().listAll().filter { it.courseId in courseIds || it.homeworkId in homeworkIds }.forEach { app.repo.deleteDraft(it.id) }
            homeworkIds.distinct().forEach { id ->
                imageIds += app.db.blockDao().listByHomework(id).mapNotNull { it.imageId }
                app.db.homeworkDao().deleteHard(id)
            }
            courseIds.forEach { cid ->
                imageIds += decodeTemplate(app.repo.getCourse(cid)?.templateJson)?.blocks.orEmpty().mapNotNull { it.imageId }
                app.repo.deleteDraft("template:$cid")
                app.db.courseDao().deleteHard(cid)
            }
            app.images.deleteAssets(imageIds.distinct())
            // 测试只临时调整排序偏好；其余偏好使用当前值。
            val current = app.prefs.snapshot()
            app.prefs.restore(current.copy(listOrders = prefs.listOrders, sortMode = prefs.sortMode))
        }
        audioIds.forEach { audioFile(app, it).delete() }
        File(app.cacheDir, "preview/$prefix.jpg").delete()
    }
    private fun course(suffix: String): Course = runBlocking {
        Course("$prefix-$suffix", app.repo.listSemesters().first().id, "$prefix-$suffix").also {
            app.repo.saveCourse(it); courseIds += it.id
        }
    }
    private fun homework(c: Course, suffix: String): String = runBlocking {
        app.repo.createHomework(c.semesterId, c.id, "$prefix-$suffix", "NONE", null, null, null,
            listOf(ContentBlock("$prefix-$suffix-block", "", text = "$prefix-$suffix")), emptyList()).also { homeworkIds += it }
    }
    private fun dragBelow(first: String, second: String) {
        val a = rule.onNodeWithTag(first).fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithTag(second).fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag(first).performTouchInput {
            down(center); advanceEventTime(700)
            moveBy(Offset(0f, b.center.y - a.center.y), delayMillis = 350)
            advanceEventTime(150); up()
        }
    }

    @Test fun draftCardTapAndRightSwipe() {
        val c = course("draft")
        val id = homework(c, "draft-work")
        val draftId = "edit:$id"
        runBlocking { app.repo.saveDraft(EditorDraft(draftId, homeworkId = id, courseId = c.id, semesterId = c.semesterId, blocksJson = encodeBlocks(listOf(BlockJson("$prefix-text", "TEXT", "草稿内容"))))) }
        var opened: String? = null
        show { DraftsScreen(app, {}, {}, { opened = it }) }
        await("continue_draft:$draftId")
        rule.onNodeWithText("继续编辑").assertDoesNotExist()
        rule.onNodeWithText("丢弃").assertDoesNotExist()
        rule.onNodeWithTag("continue_draft:$draftId").performClick()
        rule.runOnIdle { assertEquals(id, opened) }
        rule.onNodeWithTag("swipe_draft:$draftId").performTouchInput { swipeRight() }
        rule.waitUntil(8000) { runBlocking { app.repo.getDraft(draftId) == null } }
        assertNotNull(runBlocking { app.repo.getHomework(id) })
    }

    @Test fun allThreeListsReorderAndPersist() {
        val a = course("order-a"); val b = course("order-b")
        val h1 = homework(a, "one"); val h2 = homework(a, "two")
        vm.query.value = prefix
        runBlocking { app.prefs.setListOrder("todo", listOf(h1, h2)) }
        show { TodoScreen(app, vm, {}, {}, {}) }
        await("swipe_homework:$h1")
        dragBelow("swipe_homework:$h1", "swipe_homework:$h2")
        rule.waitUntil(8000) { runBlocking { app.prefs.snapshot().listOrders["todo"]?.filter { it in listOf(h1, h2) } == listOf(h2, h1) } }
        runBlocking { val current = app.prefs.snapshot(); app.prefs.restore(current.copy(listOrders = current.listOrders + ("courses" to (listOf(a.id, b.id) + current.listOrders["courses"].orEmpty())))) }
        show { CoursesScreen(app, vm, {}, {}, {}) }
        await("swipe_course:${a.id}")
        dragBelow("swipe_course:${a.id}", "swipe_course:${b.id}")
        rule.waitUntil(8000) { runBlocking { app.prefs.snapshot().listOrders["courses"]?.take(2) == listOf(b.id, a.id) } }
        show { CourseDetailScreen(app, vm, a.id, a.semesterId, {}, onOpenHomework = {}, onNewHomework = { _, _ -> }) }
        await("drag_homework:$h1")
        dragBelow("drag_homework:$h1", "drag_homework:$h2")
        rule.waitUntil(8000) { runBlocking { app.prefs.snapshot().listOrders["course:${a.id}"] == listOf(h2, h1) } }
        show { CourseDetailScreen(app, vm, a.id, a.semesterId, {}, onOpenHomework = {}, onNewHomework = { _, _ -> }) }
        await("drag_homework:$h2")
        assertTrue(rule.onNodeWithTag("drag_homework:$h2").fetchSemanticsNode().boundsInRoot.top < rule.onNodeWithTag("drag_homework:$h1").fetchSemanticsNode().boundsInRoot.top)
        rule.onNodeWithContentDescription("课程操作").performClick()
        rule.onNodeWithText("停用").assertDoesNotExist()
        rule.onNodeWithText("课程模板").assertExists()
    }

    @Test fun templateAudioPersistsAndSharedAttachmentIsNotDeleted() {
        assertNull("真实新建草稿存在，停止测试", runBlocking { app.repo.getDraft("new") })
        val c = course("audio")
        val audioId = "$prefix-audio"
        audioIds += audioId
        val file = audioFile(app, audioId).apply { parentFile!!.mkdirs() }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("template-test.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
        runBlocking {
            app.repo.saveCourseTemplate(c.id, CourseTemplate(listOf(BlockJson("$prefix-audio-block", BlockKind.AUDIO, audioId = audioId, audioDurationMs = 1000)), 3, "19:30"))
        }
        var saved by mutableStateOf(false)
        show { if (!saved) EditorScreen(app, vm, null, c.semesterId, c.id, { saved = true }, {}) else Text("录音作业已发布") }
        await("publish_homework")
        rule.onNodeWithContentDescription("播放录音").performClick()
        rule.onNodeWithText("录音无法播放").assertDoesNotExist()
        rule.onNodeWithTag("publish_homework").performClick()
        rule.waitUntil(8000) { saved }
        runBlocking {
            val h = app.db.homeworkDao().listAllByCourse(c.id).single()
            homeworkIds += h.id
            assertEquals("19:30", h.deadlineTime)
            assertEquals(audioId, app.repo.detail(h.id)!!.question.single().audioId)
            app.repo.moveToTrash(h.id)
            app.repo.deleteForever(h.id, app.images)
            assertTrue("模板仍引用的录音不能删除", file.isFile)
            assertEquals(audioId, decodeTemplate(app.repo.getCourse(c.id)!!.templateJson)!!.blocks.single().audioId)
        }
        // 再次打开模板仍能播放，验证数据库、文件和编辑器闭环。
        show { EditorScreen(app, vm, null, c.semesterId, c.id, {}, {}, templateCourseId = c.id) }
        await("publish_homework")
        rule.onNodeWithContentDescription("播放录音").performClick()
        rule.onNodeWithText("录音无法播放").assertDoesNotExist()
    }

    @Test fun clipboardImageTemplateAndNewHomework() {
        // 不覆盖可能存在的新建草稿；存在时本测试明确停止。
        assertNull("真实新建草稿存在，停止测试", runBlocking { app.repo.getDraft("new") })
        val c = course("template")
        val file = File(app.cacheDir, "preview/$prefix.jpg").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(185, 67, 53)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        var saved by mutableStateOf(false)
        show { if (!saved) EditorScreen(app, vm, null, c.semesterId, c.id, { saved = true }, {}, templateCourseId = c.id) else Text("模板保存完成") }
        await("question_text")
        rule.onNodeWithTag("question_text").performClick()
        rule.onNodeWithTag("question_text").performTextInput("每周固定要求")
        rule.runOnUiThread {
            val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            oldClip = clipboard.primaryClip; touchedClipboard = true
            clipboard.setPrimaryClip(ClipData.newUri(app.contentResolver, "测试图片", FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)))
        }
        rule.waitUntil(8000) { rule.onAllNodesWithContentDescription("粘贴图片").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("粘贴图片").performClick()
        rule.waitUntil(8000) { runBlocking { decodeBlocks(app.repo.getDraft("template:${c.id}")?.blocksJson.orEmpty()).any { it.kind == BlockKind.IMAGE } } }
        imageIds += runBlocking { decodeBlocks(app.repo.getDraft("template:${c.id}")!!.blocksJson).mapNotNull { it.imageId } }
        rule.onNodeWithTag("deadline_button").performClick()
        rule.onNodeWithTag("weekday:5").performClick()
        rule.onNodeWithTag("weekly_confirm").performClick()
        rule.onNodeWithTag("publish_homework").performClick()
        rule.waitUntil(8000) { saved }
        val template = runBlocking { decodeTemplate(app.repo.getCourse(c.id)!!.templateJson)!! }
        assertEquals(5, template.weekday)
        assertTrue(template.blocks.any { it.kind == BlockKind.IMAGE })
        assertTrue(template.blocks.any { it.text == "每周固定要求" })
        saved = false
        show { if (!saved) EditorScreen(app, vm, null, c.semesterId, c.id, { saved = true }, {}) else Text("作业发布完成") }
        await("publish_homework")
        rule.onNodeWithTag("publish_homework").performClick()
        rule.waitUntil(8000) { saved }
        val work = runBlocking { app.db.homeworkDao().listAllByCourse(c.id).single() }
        homeworkIds += work.id
        assertEquals(template.nextDate(), work.deadlineDate)
        assertTrue(runBlocking { app.repo.detail(work.id)!!.question.any { it.kind == BlockKind.IMAGE } })
        // 已输入的正文选择模板课程后不能被覆盖。
        saved = false
        show { if (!saved) EditorScreen(app, vm, null, c.semesterId, null, { saved = true }, {}) else Text("作业发布完成") }
        await("question_text")
        rule.onNodeWithTag("question_text").performTextInput("我自己写的内容")
        rule.onNodeWithText("未分类").performClick()
        rule.onNodeWithText(c.name).performClick()
        rule.onNodeWithTag("question_text").assertTextContains("我自己写的内容")
        rule.onNodeWithTag("publish_homework").performClick()
        rule.waitUntil(8000) { saved }
        runBlocking {
            val second = app.db.homeworkDao().listAllByCourse(c.id).first { it.id != work.id }
            assertEquals(template.nextDate(), second.deadlineDate)
            assertEquals("我自己写的内容", app.repo.detail(second.id)!!.question.single().text)
        }
    }
}
