package com.taskra.util

import com.taskra.data.DeadlineType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 可注入时钟：生产用系统时钟，测试用 FakeClock。 */
interface AppClock {
    fun nowMillis(): Long
}

object SystemClock : AppClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

class FakeClock(var now: Long) : AppClock {
    override fun nowMillis(): Long = now
}

object TimeUtils {
    private val DateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DisplayDate: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
    private val DisplayDateYear: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")

    fun zoneOf(zoneId: String?): ZoneId = try {
        if (zoneId.isNullOrBlank()) ZoneId.systemDefault() else ZoneId.of(zoneId)
    } catch (_: Exception) {
        ZoneId.systemDefault()
    }

    /**
     * 截止边界（毫秒时间戳）：
     * - NONE → null
     * - DATE → 该日期在截止时区的次日 00:00（“当天结束前截止”，次日零点才逾期）
     * - DATETIME → 该日期+时间在截止时区的对应时刻
     */
    fun deadlineBoundaryMillis(
        type: String,
        date: String?,
        time: String?,
        zoneId: String?,
    ): Long? {
        if (type == DeadlineType.NONE) return null
        if (date.isNullOrBlank()) return null
        val zone = zoneOf(zoneId)
        return try {
            val d = LocalDate.parse(date, DateFmt)
            if (type == DeadlineType.DATE) {
                d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            } else {
                if (time.isNullOrBlank()) {
                    d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                } else {
                    val t = LocalTime.parse(time, TimeFmt)
                    ZonedDateTime.of(d, t, zone).toInstant().toEpochMilli()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isOverdue(
        type: String, date: String?, time: String?, zoneId: String?,
        clock: AppClock = SystemClock,
    ): Boolean {
        val b = deadlineBoundaryMillis(type, date, time, zoneId) ?: return false
        return clock.nowMillis() >= b
    }

    private fun localDateOfDeadline(date: String?): LocalDate? = try {
        if (date.isNullOrBlank()) null else LocalDate.parse(date, DateFmt)
    } catch (_: Exception) {
        null
    }

    /** “今天”按截止时区解释。 */
    fun isDueToday(type: String, date: String?, zoneId: String?, clock: AppClock = SystemClock): Boolean {
        if (type == DeadlineType.NONE) return false
        val d = localDateOfDeadline(date) ?: return false
        val zone = zoneOf(zoneId)
        val today = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate()
        return d == today
    }

    /**
     * “未来七天”：从今天起的七个自然日 [today, today+6]，不包含已逾期。
     * 按截止时区解释日期。
     */
    fun isInNext7Days(type: String, date: String?, time: String?, zoneId: String?, clock: AppClock = SystemClock): Boolean {
        if (type == DeadlineType.NONE) return false
        val d = localDateOfDeadline(date) ?: return false
        if (isOverdue(type, date, time, zoneId, clock)) return false
        val zone = zoneOf(zoneId)
        val today = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate()
        return !d.isBefore(today) && !d.isAfter(today.plusDays(6))
    }

    fun formatDeadline(type: String, date: String?, time: String?, zoneId: String?): String {
        if (type == DeadlineType.NONE || date.isNullOrBlank()) return "无截止时间"
        val d = localDateOfDeadline(date) ?: return "无截止时间"
        val zone = zoneOf(zoneId)
        val nowYear = ZonedDateTime.now(zone).year
        val datePart = if (d.year == nowYear) d.format(DisplayDate) else d.format(DisplayDateYear)
        return if (type == DeadlineType.DATETIME && !time.isNullOrBlank()) {
            "$datePart $time 截止"
        } else {
            "$datePart 当天结束前截止"
        }
    }

    fun todayString(zone: ZoneId = ZoneId.systemDefault()): String =
        LocalDate.now(zone).format(DateFmt)

    fun nowDateTimeStrings(zone: ZoneId = ZoneId.systemDefault()): Pair<String, String> {
        val z = ZonedDateTime.now(zone)
        return z.toLocalDate().format(DateFmt) to z.toLocalTime().format(TimeFmt)
    }
}
