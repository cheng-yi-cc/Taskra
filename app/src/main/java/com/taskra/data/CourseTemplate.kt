package com.taskra.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

@Serializable
data class CourseTemplate(
    val blocks: List<BlockJson> = emptyList(),
    val weekday: Int? = null,
    val time: String? = null,
) {
    /** 当天尚未到截止时刻就取当天；仅日期按当天结束计算。 */
    fun nextDate(now: ZonedDateTime = ZonedDateTime.now()): String? {
        val day = weekday?.takeIf { it in 1..7 } ?: return null
        var date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.of(day)))
        val cutoff = if (time == null) date.plusDays(1).atStartOfDay(now.zone)
            else date.atTime(LocalTime.parse(time)).atZone(now.zone)
        if (!cutoff.isAfter(now)) date = date.plusWeeks(1)
        return date.toString()
    }
}

fun decodeTemplate(raw: String?): CourseTemplate? = raw?.let {
    runCatching { BlockJsonCodec.decodeFromString<CourseTemplate>(it) }.getOrNull()
}
fun encodeTemplate(template: CourseTemplate): String = BlockJsonCodec.encodeToString(template)
