package com.taskra.util

/** 只移动本次可见的条目，筛选隐藏的条目保留原有槽位。 */
fun mergeVisibleOrder(saved: List<String>, visible: List<String>): List<String> {
    val unique = visible.distinct()
    val moving = unique.toSet()
    val queue = unique.iterator()
    val result = saved.distinct().map { if (it in moving && queue.hasNext()) queue.next() else it }.toMutableList()
    while (queue.hasNext()) result += queue.next()
    return result
}

fun <T> ordered(items: List<T>, order: List<String>, key: (T) -> String): List<T> {
    val positions = order.withIndex().associate { it.value to it.index }
    return items.sortedBy { positions[key(it)] ?: Int.MAX_VALUE }
}
