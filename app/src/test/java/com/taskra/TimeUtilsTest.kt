package com.taskra

import com.taskra.data.DeadlineType
import com.taskra.util.FakeClock
import com.taskra.util.TimeUtils
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeUtilsTest {

    private fun millis(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, zone: String = "Asia/Shanghai"): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test
    fun `仅日期作业当天不逾期_次日零点才逾期`() {
        // 9月29日 当天结束前截止
        val zone = "Asia/Shanghai"
        // 9月29日 12:00 → 不逾期
        assertFalse(
            TimeUtils.isOverdue(DeadlineType.DATE, "2026-09-29", null, zone, FakeClock(millis(2026, 9, 29, 12)))
        )
        // 9月29日 23:59 → 不逾期
        assertFalse(
            TimeUtils.isOverdue(DeadlineType.DATE, "2026-09-29", null, zone, FakeClock(millis(2026, 9, 29, 23, 59)))
        )
        // 9月30日 00:00 → 逾期
        assertTrue(
            TimeUtils.isOverdue(DeadlineType.DATE, "2026-09-29", null, zone, FakeClock(millis(2026, 9, 30, 0)))
        )
    }

    @Test
    fun `具体时间按时刻逾期`() {
        val zone = "Asia/Shanghai"
        assertFalse(
            TimeUtils.isOverdue(DeadlineType.DATETIME, "2026-09-29", "18:00", zone, FakeClock(millis(2026, 9, 29, 17, 59)))
        )
        assertTrue(
            TimeUtils.isOverdue(DeadlineType.DATETIME, "2026-09-29", "18:00", zone, FakeClock(millis(2026, 9, 29, 18, 0)))
        )
    }

    @Test
    fun `无截止时间永不逾期`() {
        assertFalse(TimeUtils.isOverdue(DeadlineType.NONE, null, null, null, FakeClock(millis(2026, 9, 29, 12))))
    }

    @Test
    fun `未来七天不包含已逾期`() {
        val zone = "Asia/Shanghai"
        val now = FakeClock(millis(2026, 9, 26, 12))
        // 今天：在七天内
        assertTrue(TimeUtils.isInNext7Days(DeadlineType.DATE, "2026-09-26", null, zone, now))
        // 6天后：在七天内
        assertTrue(TimeUtils.isInNext7Days(DeadlineType.DATE, "2026-10-02", null, zone, now))
        // 7天后：超出
        assertFalse(TimeUtils.isInNext7Days(DeadlineType.DATE, "2026-10-03", null, zone, now))
        // 已逾期：即使日期差7天内也不算
        assertFalse(TimeUtils.isInNext7Days(DeadlineType.DATE, "2026-09-20", null, zone, now))
    }

    @Test
    fun `今天判定按截止时区`() {
        val nowShanghaiNoon = FakeClock(millis(2026, 9, 26, 12, 0, "Asia/Shanghai"))
        assertTrue(TimeUtils.isDueToday(DeadlineType.DATE, "2026-09-26", "Asia/Shanghai", nowShanghaiNoon))
        assertFalse(TimeUtils.isDueToday(DeadlineType.DATE, "2026-09-27", "Asia/Shanghai", nowShanghaiNoon))
    }

    @Test
    fun `截止文案相对化表达清晰可读`() {
        val zone = "Asia/Shanghai"
        // 2026-09-26 为周六
        val now = FakeClock(millis(2026, 9, 26, 12, 0, zone))
        assertEquals("今天结束前", TimeUtils.formatDeadline(DeadlineType.DATE, "2026-09-26", null, zone, now))
        assertEquals("明天 23:59 截止", TimeUtils.formatDeadline(DeadlineType.DATETIME, "2026-09-27", "23:59", zone, now))
        assertEquals("后天结束前", TimeUtils.formatDeadline(DeadlineType.DATE, "2026-09-28", null, zone, now))
        assertEquals("昨天结束前", TimeUtils.formatDeadline(DeadlineType.DATE, "2026-09-25", null, zone, now))
        assertEquals("周三（9月30日）截止", TimeUtils.formatDeadline(DeadlineType.DATE, "2026-09-30", null, zone, now))
        assertEquals("10月10日 18:00 截止", TimeUtils.formatDeadline(DeadlineType.DATETIME, "2026-10-10", "18:00", zone, now))
        assertEquals("2027年1月5日 截止", TimeUtils.formatDeadline(DeadlineType.DATE, "2027-01-05", null, zone, now))
        assertEquals("明天 18:00", TimeUtils.formatDeadlineShort(DeadlineType.DATETIME, "2026-09-27", "18:00", zone, now))
        assertEquals("截止时间", TimeUtils.formatDeadlineShort(DeadlineType.NONE, null, null, zone, now))
    }
}
