package com.taskra

import com.taskra.data.*
import com.taskra.util.*
import org.junit.Assert.*
import org.junit.Test

class EditorDocumentTest {
    @Test fun publishedAttachment_hasTextSlots_beforeBetweenAndAfter() {
        val doc = editableDocument(listOf(
            BlockJson("image", BlockKind.IMAGE, imageId = "photo"),
            BlockJson("audio", BlockKind.AUDIO, audioId = "voice"),
        ))
        assertEquals(listOf("TEXT", "IMAGE", "TEXT", "AUDIO", "TEXT"), doc.map { it.kind })
        assertEquals(doc, editableDocument(doc))
    }
    @Test fun deleteAttachment_rejoinsTextAndMovesFormatting() {
        val doc = listOf(
            BlockJson("left", BlockKind.TEXT, "题目", spans = listOf(SpanMark(SpanType.BOLD, 0, 2))),
            BlockJson("image", BlockKind.IMAGE, imageId = "photo"),
            BlockJson("right", BlockKind.TEXT, "补充", spans = listOf(SpanMark(SpanType.HIGHLIGHT, 0, 2))),
        )
        val result = removeEditorAttachment(doc, "image").single()
        assertEquals("题目补充", result.text)
        assertEquals(listOf(SpanMark(SpanType.BOLD, 0, 2), SpanMark(SpanType.HIGHLIGHT, 2, 4)), result.spans)
    }
    @Test fun repeatedAttachmentDeletion_doesNotAccumulateBlankRows() {
        var doc = editableDocument(listOf(BlockJson("image", BlockKind.IMAGE, imageId = "photo")))
        doc = removeEditorAttachment(doc, "image")
        assertEquals(1, doc.size)
        assertEquals("", doc.single().text)
    }
    @Test fun typedWhitespaceAndNewlines_arePreserved() {
        val doc = listOf(BlockJson("left", "TEXT", "前文\n"), BlockJson("audio", "AUDIO"), BlockJson("right", "TEXT", "\n 后文"))
        assertEquals("前文\n\n 后文", removeEditorAttachment(doc, "audio").single().text)
    }
}
