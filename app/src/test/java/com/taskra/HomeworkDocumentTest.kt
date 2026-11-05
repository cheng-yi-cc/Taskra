package com.taskra

import com.taskra.data.ContentBlock
import com.taskra.data.Homework
import com.taskra.sync.homeworkDocuments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeworkDocumentTest {
    @Test fun keepsEachHomeworkAndItsOrderedBlocksTogether() {
        val homework = listOf(Homework("h1", "s", null, "A"), Homework("h2", "s", null, "B"))
        val later = ContentBlock("b2", "h1", position = 1, text = "后")
        val first = ContentBlock("b1", "h1", position = 0, text = "前")
        val other = ContentBlock("b3", "h2", position = 0, text = "另一作业")
        val documents = homeworkDocuments(homework, listOf(later, other, first))
        assertEquals(listOf(first, later), documents.getValue("h1").blocks)
        assertEquals(listOf(other), documents.getValue("h2").blocks)
    }

    @Test fun rejectsOrphanAndAmbiguousBlocksInsteadOfDroppingThem() {
        val homework = listOf(Homework("h1", "s", null, "A"))
        assertThrows(IllegalArgumentException::class.java) {
            homeworkDocuments(homework, listOf(ContentBlock("b", "missing")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            homeworkDocuments(homework, listOf(ContentBlock("b1", "h1"), ContentBlock("b2", "h1")))
        }
    }
}
