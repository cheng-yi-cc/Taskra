package com.taskra

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskra.data.BlockKind
import com.taskra.data.BlockRegion
import com.taskra.data.ContentBlock
import com.taskra.data.Course
import com.taskra.data.Semester
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 仓库往返：建学期→建课程→建作业(含文字块)→读回，验证内容块持久化。 */
@RunWith(AndroidJUnit4::class)
class RepoRoundTripTest {
    @Test
    fun blocks_roundtrip() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as TaskraApp
        val r = app.repo
        kotlinx.coroutines.runBlocking {
            val semId = "t-sem-" + UUID.randomUUID()
            r.saveSemester(Semester(semId, "T学期"))
            val courseId = "t-c-" + UUID.randomUUID()
            r.saveCourse(Course(courseId, semId, "T课程"))
            val q = listOf(
                ContentBlock("t-b-" + UUID.randomUUID(), "", BlockRegion.QUESTION, BlockKind.TEXT, 0, "往返题目", null)
            )
            val hid = r.createHomework(semId, courseId, "T作业", "NONE", null, null, null, q, emptyList())
            println("DIAG-RT created=$hid")
            val raw = r.db.blockDao().listByHomework(hid)
            println("DIAG-RT rawBlocks=${raw.size} " + raw.joinToString(";") { "${it.region}/${it.kind}/${it.text}" })
            val d = r.detail(hid)
            assertNotNull(d)
            println("DIAG-RT question=${d!!.question.size} solution=${d.solution.size}")
            assertEquals(1, d.question.size)
            assertEquals("往返题目", d.question.first().text)
            assertEquals("T作业", d.homework.title)
            assertEquals("T课程", d.course?.name)
            // 回归：完成/恢复/进出回收站不能删除内容、不能置空课程归属
            // （曾因 @Insert(REPLACE) 触发级联删除导致内容丢失，已改用 @Upsert）
            r.markDone(hid)
            val done = r.detail(hid)!!
            assertEquals("DONE", done.homework.status)
            assertEquals(1, done.question.size)
            assertEquals("往返题目", done.question.first().text)
            assertEquals(courseId, done.homework.courseId)
            r.markTodo(hid)
            r.moveToTrash(hid)
            val trashed = r.detail(hid)!!
            assertEquals(1, trashed.question.size)
            assertEquals(courseId, trashed.homework.courseId)
            r.restoreFromTrash(hid)
            val restored = r.detail(hid)!!
            assertEquals(1, restored.question.size)
            assertEquals("TODO", restored.homework.status)
            // 清理，避免干扰其他测试的待办计数
            r.db.blockDao().deleteByHomework(hid)
            r.db.homeworkDao().deleteHard(hid)
        }
    }
}
