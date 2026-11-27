package com.taskra.update

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.taskra.TaskraApp
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.TaskraMotion
import com.taskra.ui.motion.appleContentSize
import com.taskra.util.postOnMain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
internal data class GitHubAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0L,
    val digest: String? = null,
)

@Serializable
internal data class OfficialVersionDto(
    val version: String = "",
    val apk: String = "",
    val latest: String = "",
    val githubApk: String = "",
    val sha256: String = "",
    val size: Long = 0L,
    val notes: String = "",
    val updated: String = "",
    val releaseUrl: String = "",
)

data class ReleaseUpdateInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val apkUrls: List<String> = listOf(apkUrl),
    val apkSizeBytes: Long,
    val sha256: String?,
    val sha256AssetUrl: String?,
    val htmlUrl: String,
)

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateDownloadState {
        val progress: Float?
            get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else null
    }
    data class ReadyToInstall(val apkFile: File) : UpdateDownloadState
    data class Error(val message: String) : UpdateDownloadState
}

data class UpdateUiState(
    val currentVersion: String,
    val checking: Boolean = false,
    val availableUpdate: ReleaseUpdateInfo? = null,
    val showDialog: Boolean = false,
    val downloadState: UpdateDownloadState = UpdateDownloadState.Idle,
    val awaitingInstallPermission: Boolean = false,
)

object ReleaseVersionParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Regex = Regex("""\b([a-fA-F0-9]{64})\b""")

    fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    /**
     * 比较 remoteTag 是否比 currentVersion 更新。
     * 支持 X.Y.Z 以及可选预发布后缀（如 1.1.0 > 1.1.0-beta.1 > 1.0.0）。
     */
    fun isNewerVersion(currentVersion: String, remoteTag: String): Boolean {
        val cur = normalizeVersion(currentVersion)
        val rem = normalizeVersion(remoteTag)
        if (cur.isEmpty() || rem.isEmpty() || cur == rem) return false

        val curMain = cur.substringBefore('-')
        val remMain = rem.substringBefore('-')
        val curSuffix = cur.substringAfter('-', "")
        val remSuffix = rem.substringAfter('-', "")

        val curParts = curMain.split('.').map { it.toIntOrNull() ?: return false }
        val remParts = remMain.split('.').map { it.toIntOrNull() ?: return false }
        val maxLen = maxOf(curParts.size, remParts.size)
        for (i in 0 until maxLen) {
            val c = curParts.getOrElse(i) { 0 }
            val r = remParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        // 主版本号相同：正式版（无后缀）高于预发布版（有后缀）
        if (curSuffix.isNotEmpty() && remSuffix.isEmpty()) return true
        if (curSuffix.isEmpty() && remSuffix.isNotEmpty()) return false
        return remSuffix > curSuffix
    }

    fun extractSha256Hex(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().removePrefix("sha256:").removePrefix("SHA256:")
        return sha256Regex.find(cleaned)?.groupValues?.get(1)?.lowercase()
    }

    fun parseOfficialVersionJson(rawJson: String, currentVersion: String): ReleaseUpdateInfo? {
        val dto = runCatching { json.decodeFromString<OfficialVersionDto>(rawJson) }.getOrNull() ?: return null
        val versionName = normalizeVersion(dto.version)
        if (versionName.isEmpty() || versionName.contains('-')) return null
        if (!isNewerVersion(currentVersion, versionName)) return null

        val tagName = dto.version.trim().ifBlank { "v$versionName" }
        val defaultApkName = "taskra-$tagName.apk"
        val primaryRaw = dto.apk.trim().ifBlank { dto.latest.trim() }.ifBlank { defaultApkName }
        val primaryUrl = resolveOfficialUrl(primaryRaw)
        val githubUrl = dto.githubApk.trim().ifBlank {
            "https://github.com/cheng-yi-cc/Taskra/releases/download/$tagName/$defaultApkName"
        }
        val urls = buildList {
            if (primaryUrl.isNotBlank()) add(primaryUrl)
            if (githubUrl.isNotBlank() && githubUrl != primaryUrl) add(githubUrl)
        }
        if (urls.isEmpty()) return null

        val apkName = primaryRaw.substringAfterLast('/').substringBefore('?').takeIf {
            it.endsWith(".apk", ignoreCase = true) && !it.equals("taskra-latest.apk", ignoreCase = true)
        } ?: defaultApkName

        return ReleaseUpdateInfo(
            tagName = tagName,
            versionName = versionName,
            title = "Taskra $tagName",
            notes = dto.notes.trim(),
            apkName = apkName,
            apkUrl = urls.first(),
            apkUrls = urls,
            apkSizeBytes = dto.size.coerceAtLeast(0L),
            sha256 = extractSha256Hex(dto.sha256),
            sha256AssetUrl = resolveOfficialUrl("$defaultApkName.sha256"),
            htmlUrl = OFFICIAL_SITE_BASE,
        )
    }

    fun parseReleaseJson(rawJson: String, currentVersion: String): ReleaseUpdateInfo? {
        val dto = runCatching { json.decodeFromString<GitHubReleaseDto>(rawJson) }.getOrNull() ?: return null
        if (dto.draft || dto.prerelease) return null
        val versionName = normalizeVersion(dto.tagName)
        if (!isNewerVersion(currentVersion, versionName)) return null

        val tagName = dto.tagName.ifBlank { "v$versionName" }
        val exactName = "taskra-$tagName.apk"
        val apkAsset = dto.assets.firstOrNull {
            it.name.equals(exactName, ignoreCase = true) && it.browserDownloadUrl.isNotBlank()
        } ?: dto.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true) &&
                !it.name.equals("taskra-latest.apk", ignoreCase = true) &&
                it.browserDownloadUrl.isNotBlank()
        } ?: dto.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true) && it.browserDownloadUrl.isNotBlank()
        } ?: return null

        val shaAsset = dto.assets.firstOrNull {
            it.name.equals("${apkAsset.name}.sha256", ignoreCase = true) ||
                it.name.endsWith(".sha256", ignoreCase = true)
        }
        val directSha = extractSha256Hex(apkAsset.digest) ?: extractSha256Hex(dto.body)

        val mirrorUrl = if (apkAsset.name.startsWith("taskra-", ignoreCase = true)) {
            resolveOfficialUrl(apkAsset.name)
        } else {
            ""
        }
        val urls = buildList {
            if (mirrorUrl.isNotBlank()) add(mirrorUrl)
            if (apkAsset.browserDownloadUrl.isNotBlank() && apkAsset.browserDownloadUrl != mirrorUrl) {
                add(apkAsset.browserDownloadUrl)
            }
        }

        return ReleaseUpdateInfo(
            tagName = tagName,
            versionName = versionName,
            title = dto.name.ifBlank { tagName },
            notes = dto.body.trim(),
            apkName = apkAsset.name,
            apkUrl = urls.first(),
            apkUrls = urls,
            apkSizeBytes = apkAsset.size,
            sha256 = directSha,
            sha256AssetUrl = shaAsset?.browserDownloadUrl?.ifBlank { null },
            htmlUrl = OFFICIAL_SITE_BASE,
        )
    }

    internal fun resolveOfficialUrl(pathOrUrl: String): String {
        val trimmed = pathOrUrl.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            return trimmed
        }
        return OFFICIAL_SITE_BASE + trimmed.trimStart('/')
    }

    const val OFFICIAL_SITE_BASE = "https://taskra.chengyi.me/"
    const val OFFICIAL_VERSION_JSON = "https://taskra.chengyi.me/version.json"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/cheng-yi-cc/Taskra/releases/latest"
    const val FALLBACK_RELEASES_PAGE = OFFICIAL_SITE_BASE
}

class AppUpdater(private val app: TaskraApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var autoCheckedThisSession = false
    private var downloadJob: Job? = null

    val currentVersionName: String = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        info.versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

    private val _state = MutableStateFlow(UpdateUiState(currentVersion = currentVersionName))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val updatesDir: File
        get() = File(app.cacheDir, "updates").apply { mkdirs() }

    /**
     * 应用冷启动且开屏动画结束后后台静默检查一次；
     * 若无网络或无更新则静默结束，不打扰用户。
     */
    fun checkOnStartupIfNeeded() {
        if (autoCheckedThisSession) return
        autoCheckedThisSession = true
        scope.launch {
            cleanObsoleteApks(keepFileName = null)
            val result = withContext(Dispatchers.IO) { fetchLatestRelease(preferSecondaryCheck = false) }
            val info = result.getOrNull() ?: return@launch
            cleanObsoleteApks(keepFileName = info.apkName)
            val ignored = runCatching { app.prefs.getIgnoredUpdateVersion() }.getOrNull()
            val cachedState = resolveCachedDownloadState(info)
            _state.update {
                it.copy(
                    availableUpdate = info,
                    downloadState = cachedState,
                    showDialog = (ignored != info.versionName),
                )
            }
        }
    }

    /**
     * 设置页手动点击「检查更新」。
     * 若发现新版本直接弹出更新对话框；若已是最新或网络异常则通过 [onMessage] 反馈给 Snackbar。
     */
    fun checkManually(onMessage: (String) -> Unit) {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchLatestRelease(preferSecondaryCheck = true) }
            _state.update { it.copy(checking = false) }
            result.fold(
                onSuccess = { info ->
                    if (info != null) {
                        cleanObsoleteApks(keepFileName = info.apkName)
                        val cachedState = resolveCachedDownloadState(info)
                        _state.update {
                            it.copy(
                                availableUpdate = info,
                                downloadState = cachedState,
                                showDialog = true,
                            )
                        }
                    } else {
                        postOnMain { onMessage("当前已是最新版本（v$currentVersionName）") }
                    }
                },
                onFailure = { e ->
                    postOnMain { onMessage(e.message ?: "检查更新失败，请检查网络连接") }
                },
            )
        }
    }

    fun openUpdateDialog() {
        if (_state.value.availableUpdate != null) {
            _state.update { it.copy(showDialog = true) }
        }
    }

    fun dismissDialog() {
        if (_state.value.downloadState is UpdateDownloadState.Downloading) return
        _state.update { it.copy(showDialog = false, awaitingInstallPermission = false) }
    }

    fun ignoreCurrentUpdate() {
        val version = _state.value.availableUpdate?.versionName ?: return
        _state.update { it.copy(showDialog = false, awaitingInstallPermission = false) }
        scope.launch {
            runCatching { app.prefs.setIgnoredUpdateVersion(version) }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.update { it.copy(downloadState = UpdateDownloadState.Idle) }
    }

    fun startDownloadOrInstall() {
        val info = _state.value.availableUpdate ?: return
        val currentDl = _state.value.downloadState
        if (currentDl is UpdateDownloadState.Downloading) return
        if (currentDl is UpdateDownloadState.ReadyToInstall && currentDl.apkFile.exists()) {
            triggerInstall(currentDl.apkFile)
            return
        }

        downloadJob?.cancel()
        downloadJob = scope.launch {
            _state.update {
                it.copy(
                    downloadState = UpdateDownloadState.Downloading(0L, info.apkSizeBytes),
                    awaitingInstallPermission = false,
                )
            }
            val downloadResult = withContext(Dispatchers.IO) { downloadAndVerifyApk(info) }
            downloadResult.fold(
                onSuccess = { apkFile ->
                    _state.update { it.copy(downloadState = UpdateDownloadState.ReadyToInstall(apkFile)) }
                    triggerInstall(apkFile)
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(downloadState = UpdateDownloadState.Error(e.message ?: "安装包下载失败"))
                    }
                },
            )
        }
    }

    /**
     * 当用户从系统的「安装未知应用」授权页返回时，若已开启权限则自动调起安装器。
     */
    fun onAppResumed() {
        val s = _state.value
        if (!s.awaitingInstallPermission) return
        val ready = s.downloadState as? UpdateDownloadState.ReadyToInstall ?: return
        if (canInstallPackages() && ready.apkFile.exists()) {
            _state.update { it.copy(awaitingInstallPermission = false) }
            launchSystemInstaller(ready.apkFile)
        }
    }

    fun openReleaseInBrowser() {
        val url = _state.value.availableUpdate?.htmlUrl ?: ReleaseVersionParser.FALLBACK_RELEASES_PAGE
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        }
    }

    private fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    private fun triggerInstall(apkFile: File) {
        if (!canInstallPackages()) {
            _state.update { it.copy(awaitingInstallPermission = true) }
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${app.packageName}"),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(intent)
            }.onFailure {
                _state.update {
                    it.copy(
                        awaitingInstallPermission = false,
                        downloadState = UpdateDownloadState.Error("无法打开未知来源授权页，请使用浏览器下载安装"),
                    )
                }
            }
            return
        }
        launchSystemInstaller(apkFile)
    }

    private fun launchSystemInstaller(apkFile: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                app,
                "${app.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        }.onFailure { e ->
            _state.update {
                it.copy(downloadState = UpdateDownloadState.Error("调起系统安装器失败：${e.message ?: "请通过浏览器下载"}"))
            }
        }
    }

    private fun resolveCachedDownloadState(info: ReleaseUpdateInfo): UpdateDownloadState {
        val target = File(updatesDir, info.apkName)
        if (!target.exists() || target.length() <= 0L) return UpdateDownloadState.Idle
        if (info.apkSizeBytes > 0L && target.length() != info.apkSizeBytes) return UpdateDownloadState.Idle
        if (!isValidApkFile(target)) return UpdateDownloadState.Idle
        return UpdateDownloadState.ReadyToInstall(target)
    }

    private fun cleanObsoleteApks(keepFileName: String?) {
        runCatching {
            updatesDir.listFiles()?.forEach { file ->
                if (keepFileName == null || (file.name != keepFileName && !file.name.endsWith(".part"))) {
                    if (file.name != keepFileName) file.delete()
                }
            }
        }
    }

    private fun fetchLatestRelease(preferSecondaryCheck: Boolean): Result<ReleaseUpdateInfo?> {
        val officialResult = runCatching { fetchOfficialVersionJson() }
        if (officialResult.isSuccess) {
            val officialInfo = officialResult.getOrNull()
            if (officialInfo != null) {
                return Result.success(officialInfo)
            }
            if (preferSecondaryCheck) {
                val ghInfo = runCatching { fetchGitHubLatestRelease(connectTimeoutMs = 6_000, readTimeoutMs = 8_000) }.getOrNull()
                if (ghInfo != null) return Result.success(ghInfo)
            }
            return Result.success(null)
        }

        val githubResult = runCatching { fetchGitHubLatestRelease(connectTimeoutMs = 12_000, readTimeoutMs = 18_000) }
        if (githubResult.isSuccess) {
            return githubResult
        }

        val err = officialResult.exceptionOrNull() ?: githubResult.exceptionOrNull()
        return Result.failure(IOException(formatNetworkError(err, isDownload = false), err))
    }

    private fun fetchOfficialVersionJson(): ReleaseUpdateInfo? {
        val conn = openConnection(
            urlString = ReleaseVersionParser.OFFICIAL_VERSION_JSON,
            acceptJson = true,
            connectTimeoutMs = 10_000,
            readTimeoutMs = 15_000,
        )
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("官网版本检查失败（HTTP $code）")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return ReleaseVersionParser.parseOfficialVersionJson(body, currentVersionName)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchGitHubLatestRelease(
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 20_000,
    ): ReleaseUpdateInfo? {
        val conn = openConnection(
            urlString = ReleaseVersionParser.LATEST_RELEASE_API,
            acceptJson = true,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
        )
        try {
            return when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    ReleaseVersionParser.parseReleaseJson(body, currentVersionName)
                }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                HttpURLConnection.HTTP_FORBIDDEN, 429 -> {
                    throw IOException("GitHub 接口请求受限（HTTP $code），请稍后再试")
                }
                else -> {
                    throw IOException("检查更新失败（HTTP $code）")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadAndVerifyApk(info: ReleaseUpdateInfo): Result<File> = runCatching {
        val expectedSha256 = info.sha256 ?: info.sha256AssetUrl?.let { fetchSha256Text(it) }
        val targetFile = File(updatesDir, info.apkName)
        val partFile = File(updatesDir, "${info.apkName}.part")

        if (targetFile.exists() && isValidApkFile(targetFile)) {
            if (expectedSha256 == null || computeFileSha256(targetFile).equals(expectedSha256, ignoreCase = true)) {
                return@runCatching targetFile
            }
            targetFile.delete()
        }

        val candidates = info.apkUrls.ifEmpty { listOf(info.apkUrl) }.distinct()
        var lastError: Throwable? = null

        for (candidateUrl in candidates) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                coroutineContext.ensureActive()
                try {
                    downloadFromUrlWithResume(
                        urlString = candidateUrl,
                        partFile = partFile,
                        expectedSizeBytes = info.apkSizeBytes,
                    )
                    val actualSha256 = computeFileSha256(partFile)
                    if (!expectedSha256.isNullOrBlank() && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                        partFile.delete()
                        throw IOException("安装包 SHA-256 校验未通过，已拦截安装")
                    }
                    if (!isValidApkFile(partFile)) {
                        partFile.delete()
                        throw IOException("下载的 APK 文件不完整或签名不匹配")
                    }
                    if (targetFile.exists()) targetFile.delete()
                    if (!partFile.renameTo(targetFile)) {
                        partFile.copyTo(targetFile, overwrite = true)
                        partFile.delete()
                    }
                    return@runCatching targetFile
                } catch (e: CancellationException) {
                    if (partFile.exists()) partFile.delete()
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    val isVerificationFailure = e.message?.contains("校验") == true || e.message?.contains("签名") == true
                    if (isVerificationFailure) break
                }
            }
        }

        throw IOException(formatNetworkError(lastError, isDownload = true), lastError)
    }

    private suspend fun downloadFromUrlWithResume(
        urlString: String,
        partFile: File,
        expectedSizeBytes: Long,
    ) {
        var existingBytes = if (partFile.exists()) partFile.length() else 0L
        if (expectedSizeBytes > 0L && existingBytes > expectedSizeBytes) {
            partFile.delete()
            existingBytes = 0L
        }

        val conn = openConnection(
            urlString = urlString,
            acceptJson = false,
            connectTimeoutMs = 15_000,
            readTimeoutMs = 45_000,
            rangeStartBytes = existingBytes.takeIf { it > 0L },
        )
        try {
            val code = conn.responseCode
            if (code == 416 && existingBytes > 0L) {
                partFile.delete()
                throw IOException("续传偏移失效，需重新下载")
            }
            if (code !in 200..299) {
                throw IOException("下载失败（HTTP $code）")
            }
            val contentType = conn.contentType.orEmpty().lowercase()
            if (contentType.contains("text/html")) {
                partFile.delete()
                throw IOException("下载源返回了无效页面内容")
            }

            val isResume = (code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L)
            val contentLength = conn.contentLengthLong.takeIf { it > 0L } ?: -1L
            val totalBytes = when {
                isResume -> parseTotalBytesFromContentRange(conn.getHeaderField("Content-Range"))
                    ?: if (contentLength > 0L) existingBytes + contentLength else expectedSizeBytes
                contentLength > 0L -> contentLength
                else -> expectedSizeBytes
            }

            var downloaded = if (isResume) existingBytes else 0L
            var lastEmitTime = 0L
            if (downloaded > 0L) {
                _state.update {
                    it.copy(downloadState = UpdateDownloadState.Downloading(downloaded, totalBytes))
                }
            }

            conn.inputStream.buffered().use { input ->
                FileOutputStream(partFile, isResume).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime >= 80L || (totalBytes > 0L && downloaded >= totalBytes)) {
                            lastEmitTime = now
                            _state.update {
                                it.copy(downloadState = UpdateDownloadState.Downloading(downloaded, totalBytes))
                            }
                        }
                    }
                    output.fd.sync()
                }
            }

            if (totalBytes > 0L && partFile.length() < totalBytes) {
                throw IOException("下载连接中断（已下载 ${formatBytes(partFile.length())} / ${formatBytes(totalBytes)}）")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseTotalBytesFromContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val total = header.substringAfter('/', "").trim()
        return total.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun fetchSha256Text(url: String): String? = runCatching {
        val conn = openConnection(url, acceptJson = false, connectTimeoutMs = 10_000, readTimeoutMs = 15_000)
        try {
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                ReleaseVersionParser.extractSha256Hex(text)
            } else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun isValidApkFile(file: File): Boolean = runCatching {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageArchiveInfo(file.absolutePath, flags)
        } ?: return@runCatching false
        if (archiveInfo.packageName != app.packageName) return@runCatching false
        val installedInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(app.packageName, flags)
        }
        fun signatures(info: android.content.pm.PackageInfo): Set<String> {
            val values = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures
            return values.orEmpty().map { it.toCharsString() }.toSet()
        }
        val installed = signatures(installedInfo)
        installed.isNotEmpty() && installed == signatures(archiveInfo)
    }.getOrDefault(false)

    private fun openConnection(
        urlString: String,
        acceptJson: Boolean,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 30_000,
        rangeStartBytes: Long? = null,
    ): HttpURLConnection {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 8) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Taskra-Android/$currentVersionName")
                setRequestProperty("Cache-Control", "no-cache")
                if (acceptJson) {
                    setRequestProperty("Accept", "application/vnd.github+json, application/json")
                }
                if (rangeStartBytes != null && rangeStartBytes > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStartBytes-")
                }
            }
            val code = conn.responseCode
            if (code in 301..308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("重定向缺少目标地址")
                currentUrl = URL(URL(currentUrl), location).toString()
                redirects++
                continue
            }
            return conn
        }
        throw IOException("重定向次数过多")
    }

    private fun formatNetworkError(error: Throwable?, isDownload: Boolean): String {
        val raw = error?.message.orEmpty()
        return when {
            error is SocketTimeoutException || raw.contains("timeout", ignoreCase = true) -> {
                if (isDownload) "安装包下载超时，请重试或点击「浏览器下载」" else "检查更新超时，请检查网络连接后重试"
            }
            error is UnknownHostException || error is ConnectException -> {
                if (isDownload) "无法连接下载服务器，请检查网络后重试" else "无法连接更新服务器，请检查网络连接"
            }
            error is SSLException -> {
                "安全连接建立失败，请检查系统时间或网络环境"
            }
            raw.any { it.code > 127 } -> raw
            else -> if (isDownload) "安装包下载失败，请重试或点击「浏览器下载」" else "检查更新失败，请检查网络连接"
        }
    }
}

@Composable
fun UpdateDialogHost(updater: AppUpdater) {
    val state by updater.state.collectAsState()
    val info = state.availableUpdate
    if (!state.showDialog || info == null) return

    val reduced = LocalReduceMotion.current
    val dl = state.downloadState
    val isDownloading = dl is UpdateDownloadState.Downloading

    AlertDialog(
        onDismissRequest = {
            if (!isDownloading) updater.dismissDialog()
        },
        title = {
            Text("发现新版本 v${info.versionName}")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .appleContentSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "当前版本：v${state.currentVersion}" +
                        if (info.apkSizeBytes > 0L) " · 安装包 ${formatBytes(info.apkSizeBytes)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.notes.isNotBlank()) {
                    Text(
                        info.notes,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        "包含体验优化与问题修复，建议更新至最新版本。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                AnimatedContent(
                    targetState = dl::class,
                    transitionSpec = { TaskraMotion.crossfadeSpec(reduced) },
                    label = "updateDownloadPhase",
                ) {
                    when (val cur = state.downloadState) {
                        is UpdateDownloadState.Downloading -> {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Spacer(Modifier.height(4.dp))
                                val p = cur.progress
                                if (p != null) {
                                    val animatedProgress by animateFloatAsState(
                                        targetValue = p,
                                        animationSpec = tween(if (reduced) 0 else 120, easing = TaskraMotion.Standard),
                                        label = "updateDlProgress",
                                    )
                                    LinearProgressIndicator(
                                        progress = { animatedProgress },
                                        modifier = Modifier.fillMaxWidth().testTag("update_progress"),
                                    )
                                    Text(
                                        "正在下载并校验：${(p * 100).toInt()}%（${formatBytes(cur.downloadedBytes)} / ${formatBytes(cur.totalBytes)}）",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("update_progress"))
                                    Text(
                                        "正在下载并校验：已下载 ${formatBytes(cur.downloadedBytes)}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        is UpdateDownloadState.ReadyToInstall -> {
                            Text(
                                if (state.awaitingInstallPermission) {
                                    "安装包已通过 SHA-256 校验，请在系统设置中允许「安装未知应用」后完成安装。"
                                } else {
                                    "安装包已就绪并通过完整性校验，点击下方按钮立即安装。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        is UpdateDownloadState.Error -> {
                            Text(
                                cur.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        UpdateDownloadState.Idle -> Unit
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (dl is UpdateDownloadState.Error) {
                    TextButton(
                        onClick = { updater.openReleaseInBrowser() },
                        modifier = Modifier.testTag("update_browser_fallback"),
                    ) {
                        Text("浏览器下载")
                    }
                }
                if (!isDownloading) {
                    TextButton(
                        onClick = { updater.startDownloadOrInstall() },
                        modifier = Modifier.testTag("update_confirm"),
                    ) {
                        val label = when (dl) {
                            is UpdateDownloadState.ReadyToInstall -> "立即安装"
                            is UpdateDownloadState.Error -> "重试下载"
                            else -> "立即更新"
                        }
                        Text(label)
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isDownloading) {
                    TextButton(
                        onClick = { updater.cancelDownload() },
                        modifier = Modifier.testTag("update_cancel_download"),
                    ) {
                        Text("取消下载")
                    }
                } else {
                    TextButton(
                        onClick = { updater.ignoreCurrentUpdate() },
                        modifier = Modifier.testTag("update_ignore"),
                    ) {
                        Text("忽略此版本")
                    }
                    TextButton(
                        onClick = { updater.dismissDialog() },
                        modifier = Modifier.testTag("update_later"),
                    ) {
                        Text("稍后")
                    }
                }
            }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= 1.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
    val kb = bytes / 1024.0
    return String.format(java.util.Locale.US, "%.0f KB", kb)
}
