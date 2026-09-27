package com.taskra

import com.taskra.data.Course
import com.taskra.data.DeadlineType
import com.taskra.data.Homework
import com.taskra.data.Status
import com.taskra.ui.filterTodo
import com.taskra.util.FakeClock
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FilterTodoTest {
    private val zone = "Asia/Shanghai"
    private fun t(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun hw(id: String, courseId: String? = "c1", title: String = id, type: String = DeadlineType.NONE, date: String? = null) =
        Homework(id, "s1", courseId, title, Status.TODO, type, date, null, zone, 100, 100)

    @Test
    fun `课程加截止组合筛选`() {
        val now = FakeClock(t(2026, 9, 26, 12))
        val courses = mapOf("c1" to Course("c1", "s1", "数学"), "c2" to Course("c2", "s1", "英语"))
        val all = listOf(
            hw("1", "c1", type = DeadlineType.DATE, date = "2026-09-20"), // 逾期
            hw("2", "c1", type = DeadlineType.DATE, date = "2026-09-26"), // 今天
            hw("3", "c2", type = DeadlineType.DATE, date = "2026-09-26"), // 今天但他课
            hw("4", null), // 未分类无截止
        )
        // 课程c1 + 今天
        val r = filterTodo(all, courses, "", "c1", "TODAY", null, emptyMap(), clock = now)
        assertEquals(listOf("2"), r.map { it.id })
        // 未分类
        val u = filterTodo(all, courses, "", "UNCAT", "ALL", null, emptyMap(), clock = now)
        assertEquals(listOf("4"), u.map { it.id })
        // 已逾期
        val o = filterTodo(all, courses, "", null, "OVERDUE", null, emptyMap(), clock = now)
        assertEquals(listOf("1"), o.map { it.id })
    }

    @Test
    fun `搜索标题与课程名`() {
        val now = FakeClock(t(2026, 9, 26, 12))
        val courses = mapOf("c1" to Course("c1", "s1", "机械设计"))
        val all = listOf(
            hw("1", "c1", title = "齿轮大作业"),
            hw("2", "c1", title = "随堂测验"),
        )
        assertEquals(listOf("1"), filterTodo(all, courses, "齿轮", null, "ALL", null, emptyMap(), clock = now).map { it.id })
        assertEquals(2, filterTodo(all, courses, "机械", null, "ALL", null, emptyMap(), clock = now).size)
    }
}
