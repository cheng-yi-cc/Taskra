package com.taskra

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskra.data.BlockJson
import com.taskra.data.BlockKind
import com.taskra.data.EditorDraft
import com.taskra.data.decodeBlocks
import com.taskra.data.encodeBlocks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteDraftTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun anotherDevicesDraftCanBeEditedAndPublishedWithoutDeletingLocalDraft() {
        val app = rule.activity.application as TaskraApp
        val remoteId = "new:remote_device"
        fun draft(id: String, text: String) = EditorDraft(
            id = id,
            blocksJson = encodeBlocks(listOf(BlockJson("block-$id", BlockKind.TEXT, text))),
        )
        runBlocking {
            app.repo.saveDraft(draft("new", "本机独立草稿"))
            app.repo.saveDraft(draft(remoteId, "远端独立草稿"))
        }
        rule.onNodeWithContentDescription("草稿箱").performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("continue_draft:$remoteId").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("continue_draft:$remoteId").performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("question_text").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("question_text")[0].assertTextContains("远端独立草稿")
        rule.onAllNodesWithTag("question_text")[0].performTextInput("补充")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitUntil(8000) {
            runBlocking { app.repo.getDraft(remoteId)?.blocksJson?.let(::decodeBlocks)?.any { it.text.contains("补充") } == true }
        }
        assertTrue(runBlocking { decodeBlocks(app.repo.getDraft("new")!!.blocksJson).first().text == "本机独立草稿" })
        rule.onNodeWithTag("continue_draft:$remoteId").performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("publish_homework").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("publish_homework").performClick()
        rule.waitUntil(8000) { runBlocking { app.repo.getDraft(remoteId) == null } }
        runBlocking {
            assertNull(app.repo.getDraft(remoteId))
            assertNotNull(app.repo.getDraft("new"))
            assertTrue(app.db.homeworkDao().listActive().any { it.title.contains("远端独立草稿") })
        }
    }
}
