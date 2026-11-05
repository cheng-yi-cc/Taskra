package com.taskra.sync

import com.taskra.data.ContentBlock
import com.taskra.data.Homework
import kotlinx.serialization.Serializable

/** 正式作业及其所有正文、历史内容块作为同一个同步单位。 */
@Serializable
internal data class HomeworkDocument(val homework: Homework, val blocks: List<ContentBlock>)

/**
 * 从同一个 Room 事务取得的行构造同步单位。
 * 数据不完整或顺序含糊时拒绝生成，不能自动丢弃孤立内容块。
 */
internal fun homeworkDocuments(
    homework: List<Homework>,
    blocks: List<ContentBlock>,
): Map<String, HomeworkDocument> {
    val byId = homework.associateBy { it.id }
    require(byId.size == homework.size) { "作业标识重复" }
    require(blocks.map { it.id }.toSet().size == blocks.size) { "内容块标识重复" }
    require(blocks.all { it.homeworkId in byId }) { "内容块缺少关联作业" }
    val groups = blocks.groupBy { it.homeworkId }
    return byId.mapValues { (id, item) ->
        val parts = groups[id].orEmpty()
        require(parts.map { it.region to it.position }.toSet().size == parts.size) { "内容块顺序存在冲突" }
        HomeworkDocument(item, parts.sortedWith(compareBy(ContentBlock::region, ContentBlock::position)))
    }
}
