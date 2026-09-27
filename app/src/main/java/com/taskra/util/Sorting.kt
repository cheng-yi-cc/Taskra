package com.taskra.util

import com.taskra.data.Homework
import com.taskra.data.SortMode

object HomeworkSort {
    /**
     * 截止优先：有截止按边界升序（逾期自然在前，拖欠更久优先），无截止放最后；
     * 边界相同按 createdAt 升序，再按 id 稳定排序。
     * NEWEST/OLDEST：严格按 createdAt 倒序/正序（+id），不再置顶逾期。
     */
    fun sort(list: List<Homework>, mode: String, clock: AppClock = SystemClock): List<Homework> {
        return when (mode) {
            SortMode.NEWEST -> list.sortedWith(compareByDescending<Homework> { it.createdAt }.thenBy { it.id })
            SortMode.OLDEST -> list.sortedWith(compareBy<Homework> { it.createdAt }.thenBy { it.id })
            else -> list.sortedWith(
                compareBy<Homework> {
                    TimeUtils.deadlineBoundaryMillis(
                        it.deadlineType, it.deadlineDate, it.deadlineTime, it.deadlineZoneId
                    ) ?: Long.MAX_VALUE
                }.thenBy { it.createdAt }.thenBy { it.id }
            )
        }
    }
}

object TitleGenerator {
    /** 标题留空时自动生成：首段非空文字→截断；纯图片→课程名+日期+作业。 */
    fun generate(firstText: String?, courseName: String?, dateStr: String?): String {
        val t = firstText?.lines()?.firstOrNull { it.isNotBlank() }?.trim()
        if (!t.isNullOrBlank()) return if (t.length > 30) t.take(30) + "…" else t
        val d = dateStr?.take(10) ?: ""
        return if (!courseName.isNullOrBlank() && d.isNotBlank()) "$courseName $d 作业"
        else if (!courseName.isNullOrBlank()) "${courseName}作业"
        else "未命名作业"
    }
}
