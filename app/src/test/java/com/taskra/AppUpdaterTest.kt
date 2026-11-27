package com.taskra

import com.taskra.update.ReleaseVersionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun versionComparisonHandlesStandardAndTaggedSemver() {
        assertTrue(ReleaseVersionParser.isNewerVersion("1.0.0", "v1.0.1"))
        assertTrue(ReleaseVersionParser.isNewerVersion("1.0.0", "1.1.0"))
        assertTrue(ReleaseVersionParser.isNewerVersion("1.0.9", "v2.0.0"))
        assertFalse(ReleaseVersionParser.isNewerVersion("1.0.0", "v1.0.0"))
        assertFalse(ReleaseVersionParser.isNewerVersion("1.2.0", "v1.1.9"))
        assertTrue(ReleaseVersionParser.isNewerVersion("1.1.0-beta.1", "v1.1.0"))
        assertFalse(ReleaseVersionParser.isNewerVersion("1.1.0", "v1.1.0-beta.1"))
    }

    @Test
    fun parseReleaseJsonExtractsApkAndSha256() {
        val sampleSha = "a3f5c8e9d1b2047689ab12cd34ef567890abcdef1234567890abcdef12345678"
        val json = """
            {
              "tag_name": "v1.2.0",
              "name": "Taskra v1.2.0",
              "body": "### 更新内容\n- 新增应用内自动检查更新",
              "html_url": "https://github.com/cheng-yi-cc/Taskra/releases/tag/v1.2.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "taskra-v1.2.0.apk",
                  "browser_download_url": "https://github.com/cheng-yi-cc/Taskra/releases/download/v1.2.0/taskra-v1.2.0.apk",
                  "size": 12582912,
                  "digest": "sha256:$sampleSha"
                },
                {
                  "name": "taskra-v1.2.0.apk.sha256",
                  "browser_download_url": "https://github.com/cheng-yi-cc/Taskra/releases/download/v1.2.0/taskra-v1.2.0.apk.sha256",
                  "size": 85
                }
              ]
            }
        """.trimIndent()

        val info = ReleaseVersionParser.parseReleaseJson(json, currentVersion = "1.0.0")
        assertNotNull(info)
        assertEquals("v1.2.0", info!!.tagName)
        assertEquals("1.2.0", info.versionName)
        assertEquals("taskra-v1.2.0.apk", info.apkName)
        assertEquals(12582912L, info.apkSizeBytes)
        assertEquals(sampleSha, info.sha256)
        assertEquals(
            "https://github.com/cheng-yi-cc/Taskra/releases/download/v1.2.0/taskra-v1.2.0.apk.sha256",
            info.sha256AssetUrl,
        )

        // 当本地版本已等于或高于远端版本时返回 null
        assertNull(ReleaseVersionParser.parseReleaseJson(json, currentVersion = "1.2.0"))
        assertNull(ReleaseVersionParser.parseReleaseJson(json, currentVersion = "1.3.0"))
    }

    @Test
    fun parseReleaseJsonIgnoresDraftOrPrerelease() {
        val draftJson = """
            {
              "tag_name": "v2.0.0",
              "draft": true,
              "prerelease": false,
              "assets": [
                {
                  "name": "taskra-v2.0.0.apk",
                  "browser_download_url": "https://example.com/taskra-v2.0.0.apk"
                }
              ]
            }
        """.trimIndent()
        assertNull(ReleaseVersionParser.parseReleaseJson(draftJson, currentVersion = "1.0.0"))
    }

    @Test
    fun parseOfficialVersionJsonPrioritizesCloudflareMirrorWithGitHubFallback() {
        val sampleSha = "36140d6f2da762483e11fe16fed952f2abba555647133cebd2998e8e84edb7c2"
        val json = """
            {
              "version": "v1.0.5",
              "apk": "taskra-v1.0.5.apk",
              "latest": "taskra-latest.apk",
              "githubApk": "https://github.com/cheng-yi-cc/Taskra/releases/download/v1.0.5/taskra-v1.0.5.apk",
              "sha256": "$sampleSha",
              "size": 15032686,
              "updated": "2026-11-27T13:30:00Z",
              "releaseUrl": "https://github.com/cheng-yi-cc/Taskra/releases/tag/v1.0.5"
            }
        """.trimIndent()

        val info = ReleaseVersionParser.parseOfficialVersionJson(json, currentVersion = "1.0.4-rc.1")
        assertNotNull(info)
        assertEquals("v1.0.5", info!!.tagName)
        assertEquals("1.0.5", info.versionName)
        assertEquals("taskra-v1.0.5.apk", info.apkName)
        assertEquals(15032686L, info.apkSizeBytes)
        assertEquals(sampleSha, info.sha256)
        assertEquals(
            listOf(
                "https://taskra.chengyi.me/taskra-v1.0.5.apk",
                "https://github.com/cheng-yi-cc/Taskra/releases/download/v1.0.5/taskra-v1.0.5.apk",
            ),
            info.apkUrls,
        )
        assertNull(ReleaseVersionParser.parseOfficialVersionJson(json, currentVersion = "1.0.5"))
    }

    @Test
    fun parseReleaseJsonPrefersVersionedApkOverLatestAndAddsOfficialMirror() {
        val json = """
            {
              "tag_name": "v1.0.5",
              "name": "v1.0.5",
              "body": "修复更新超时",
              "html_url": "https://github.com/cheng-yi-cc/Taskra/releases/tag/v1.0.5",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "taskra-latest.apk",
                  "browser_download_url": "https://github.com/cheng-yi-cc/Taskra/releases/download/v1.0.5/taskra-latest.apk",
                  "size": 15032686
                },
                {
                  "name": "taskra-v1.0.5.apk",
                  "browser_download_url": "https://github.com/cheng-yi-cc/Taskra/releases/download/v1.0.5/taskra-v1.0.5.apk",
                  "size": 15032686
                }
              ]
            }
        """.trimIndent()

        val info = ReleaseVersionParser.parseReleaseJson(json, currentVersion = "1.0.4")
        assertNotNull(info)
        assertEquals("taskra-v1.0.5.apk", info!!.apkName)
        assertEquals(
            listOf(
                "https://taskra.chengyi.me/taskra-v1.0.5.apk",
                "https://github.com/cheng-yi-cc/Taskra/releases/download/v1.0.5/taskra-v1.0.5.apk",
            ),
            info.apkUrls,
        )
    }
}
