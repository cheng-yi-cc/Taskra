package com.taskra

import com.taskra.data.DeadlineType
import com.taskra.data.Homework
import com.taskra.data.SortMode
import com.taskra.data.Status
import com.taskra.util.FakeClock
import com.taskra.util.HomeworkSort
import com.taskra.util.TitleGenerator
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

private fun hw(
    id: String,
    createdAt: Long,
    type: String = DeadlineType.NONE,
    date: String? = null,
    time: String? = null,
) = Homework(
    id = id, semesterId = "s1", courseId = null, title = id,
    status = Status.TODO, deadlineType = type, deadlineDate = date,
    deadlineTime = time, deadlineZoneId = "Asia/Shanghai",
    createdAt = createdAt, updatedAt = createdAt,
)

class HomeworkSortTest {
    private val zone = "Asia/Shanghai"
    private fun t(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test
    fun `截止优先_逾期在前_拖欠更久优先_未来随后_无截止最后`() {
        val now = FakeClock(t(2026, 9, 26, 12))
        val oldOverdue = hw("old", createdAt = 100, type = DeadlineType.DATE, date = "2026-09-10")
        val newOverdue = hw("new", createdAt = 200, type = DeadlineType.DATE, date = "2026-09-25")
        val future = hw("fut", createdAt = 50, type = DeadlineType.DATE, date = "2026-09-30")
        val none = hw("none", createdAt = 10)
        val sorted = HomeworkSort.sort(listOf(none, future, newOverdue, oldOverdue), SortMode.DEADLINE, now)
        assertEquals(listOf("old", "new", "fut", "none"), sorted.map { it.id })
    }

    @Test
    fun `截止相同按创建时间_再按ID稳定排序`() {
        val now = FakeClock(t(2026, 9, 26, 12))
        val a = hw("b", createdAt = 100, type = DeadlineType.DATE, date = "2026-09-28")
        val b = hw("a", createdAt = 100, type = DeadlineType.DATE, date = "2026-09-28")
        val c = hw("c", createdAt = 50, type = DeadlineType.DATE, date = "2026-09-28")
        val sorted = HomeworkSort.sort(listOf(a, b, c), SortMode.DEADLINE, now)
        assertEquals(listOf("c", "a", "b"), sorted.map { it.id })
    }

    @Test
    fun `最新记录优先严格按记录时间_逾期不置顶`() {
        val now = FakeClock(t(2026, 9, 26, 12))
        val overdue = hw("overdue", createdAt = 300, type = DeadlineType.DATE, date = "2026-09-10")
        val fresh = hw("fresh", createdAt = 100, type = DeadlineType.DATE, date = "2026-09-30")
        val sorted = HomeworkSort.sort(listOf(overdue, fresh), SortMode.NEWEST, now)
        assertEquals(listOf("overdue", "fresh"), sorted.map { it.id })
        val oldest = HomeworkSort.sort(listOf(overdue, fresh), SortMode.OLDEST, now)
        assertEquals(listOf("fresh", "overdue"), oldest.map { it.id })
    }

    @Test
    fun `标题生成_首段文字优先_纯图片用课程名日期`() {
        assertEquals("第一段", TitleGenerator.generate("第一段\n第二段", "数学", "2026-09-26"))
        assertEquals("数学 2026-09-26 作业", TitleGenerator.generate(null, "数学", "2026-09-26"))
        assertEquals("未命名作业", TitleGenerator.generate("  \n ", null, null))
    }
}
