package com.taskra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskra.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseTrashTest {
    @Test fun courseTrash_restore_preservesStatusesContentAndOlderTrash() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repo = TaskraRepository(db)
        try {
            repo.saveSemester(Semester("s", "旧学期"))
            repo.saveCourse(Course("c", "s", "数学"))
            fun block() = listOf(ContentBlock("", "", kind = BlockKind.TEXT, text = "原始内容", spanJson = "[]"))
            val todo = repo.createHomework("s", "c", "未完成", "NONE", null, null, null, block(), emptyList())
            val done = repo.createHomework("s", "c", "已完成", "NONE", null, null, null, block().map { it.copy(id = "done-block") }, emptyList())
            val older = repo.createHomework("s", "c", "已单独删除", "NONE", null, null, null, emptyList(), emptyList())
            repo.markDone(done)
            repo.moveToTrash(older)
            val oldTrashTime = repo.getHomework(older)!!.deletedAt
            val original = repo.getHomework(done)!!
            repo.moveCourseToTrash("c")
            val stamp = repo.getCourse("c")!!.deletedAt
            assertNotNull(stamp)
            assertEquals(stamp, repo.getHomework(todo)!!.deletedAt)
            assertEquals(stamp, repo.getHomework(done)!!.deletedAt)
            assertEquals(oldTrashTime, repo.getHomework(older)!!.deletedAt)
            assertEquals(Status.DONE, repo.getHomework(done)!!.status)
            repo.restoreCourse("c")
            assertNull(repo.getCourse("c")!!.deletedAt)
            assertNull(repo.getHomework(todo)!!.deletedAt)
            assertNull(repo.getHomework(done)!!.deletedAt)
            assertEquals(oldTrashTime, repo.getHomework(older)!!.deletedAt)
            assertEquals(original.createdAt, repo.getHomework(done)!!.createdAt)
            assertEquals(original.completedAt, repo.getHomework(done)!!.completedAt)
            assertEquals("原始内容", repo.detail(done)!!.question.single().text)
            repo.moveToTrash(todo)
            assertEquals(Status.TODO, repo.getHomework(todo)!!.status)
            repo.restoreFromTrash(todo)
            assertEquals(Status.TODO, repo.getHomework(todo)!!.status)
        } finally { db.close() }
    }
}
