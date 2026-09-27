package com.taskra

import com.taskra.data.BackupData
import com.taskra.data.BackupManifest
import com.taskra.data.ContentBlock
import com.taskra.data.Course
import com.taskra.data.Homework
import com.taskra.data.Semester
import com.taskra.data.UserPrefs
import com.taskra.data.sha256Bytes
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `manifest与data可序列化往返`() {
        val data = BackupData(
            semesters = listOf(Semester("s1", "2026 秋季")),
            courses = listOf(Course("c1", "s1", "数学")),
            homework = listOf(Homework("h1", "s1", "c1", "作业1")),
            blocks = emptyList(),
            images = emptyList(),
            drafts = emptyList(),
            sessions = emptyList(),
            items = emptyList(),
            prefs = UserPrefs(mottoText = "先做好眼前这一件。"),
        )
        val bytes = json.encodeToString(data).toByteArray(Charsets.UTF_8)
        val manifest = BackupManifest(
            formatVersion = 1,
            counts = mapOf("homework" to 1),
            sha256 = mapOf("data.json" to sha256Bytes(bytes)),
        )
        // 往返
        val m2 = json.decodeFromString<BackupManifest>(json.encodeToString(manifest))
        assertEquals(1, m2.formatVersion)
        assertEquals(sha256Bytes(bytes), m2.sha256["data.json"])
        val d2 = json.decodeFromString<BackupData>(bytes.toString(Charsets.UTF_8))
        assertEquals("先做好眼前这一件。", d2.prefs.mottoText)
        assertEquals("作业1", d2.homework.first().title)
    }

    @Test
    fun `sha256稳定`() {
        assertEquals(sha256Bytes("abc".toByteArray()), sha256Bytes("abc".toByteArray()))
        assertNotEquals(sha256Bytes("a".toByteArray()), sha256Bytes("b".toByteArray()))
    }

    @Test
    fun `老备份缺少spanJson时默认空数组`() {
        val b = json.decodeFromString<ContentBlock>("""{"id":"b1","homeworkId":"h1","text":"hi"}""")
        assertEquals("[]", b.spanJson)
        assertEquals("QUESTION", b.region)
        assertEquals("TEXT", b.kind)
    }
}
