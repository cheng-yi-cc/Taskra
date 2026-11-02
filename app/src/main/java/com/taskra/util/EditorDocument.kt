package com.taskra.util

import com.taskra.data.BlockJson
import com.taskra.data.BlockKind
import java.util.UUID

private fun textSlot() = BlockJson(UUID.randomUUID().toString(), BlockKind.TEXT, "")

/** 已发布文档不保存空块，重新编辑时补回附件前后可点击的输入位置。 */
fun editableDocument(blocks: List<BlockJson>): List<BlockJson> = buildList {
    blocks.forEach { block ->
        if (block.kind != BlockKind.TEXT && (isEmpty() || last().kind != BlockKind.TEXT)) add(textSlot())
        // 清理旧版遗留的相邻空输入块，但不吞掉用户输入的空格和换行。
        if (block.kind == BlockKind.TEXT && lastOrNull()?.kind == BlockKind.TEXT &&
            (block.text.isEmpty() || last().text.isEmpty())) {
            if (last().text.isEmpty()) { removeAt(lastIndex); add(block) }
        } else add(block)
    }
    if (isEmpty() || last().kind != BlockKind.TEXT) add(textSlot())
}

/** 删除附件时接回原来被拆开的文字，右侧富文本范围随合并偏移。 */
fun removeEditorAttachment(blocks: List<BlockJson>, id: String): List<BlockJson> {
    val index = blocks.indexOfFirst { it.id == id && it.kind != BlockKind.TEXT }
    if (index < 0) return blocks
    val result = blocks.toMutableList()
    result.removeAt(index)
    val before = result.getOrNull(index - 1)
    val after = result.getOrNull(index)
    if (before?.kind == BlockKind.TEXT && after?.kind == BlockKind.TEXT) {
        result[index - 1] = before.copy(
            text = before.text + after.text,
            spans = before.spans + after.spans.map { it.copy(start = it.start + before.text.length, end = it.end + before.text.length) },
        )
        result.removeAt(index)
    }
    return editableDocument(result)
}
