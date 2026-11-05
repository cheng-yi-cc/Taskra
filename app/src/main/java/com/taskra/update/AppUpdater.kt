package com.taskra.update

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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

data class ReleaseUpdateInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
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

    fun parseReleaseJson(rawJson: String, currentVersion: String): ReleaseUpdateInfo? {
        val dto = runCatching { json.decodeFromString<GitHubReleaseDto>(rawJson) }.getOrNull() ?: return null
        if (dto.draft || dto.prerelease) return null
        val versionName = normalizeVersion(dto.tagName)
        if (!isNewerVersion(currentVersion, versionName)) return null

        val apkAsset = dto.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true) && it.browserDownloadUrl.isNotBlank()
        } ?: return null

        val shaAsset = dto.assets.firstOrNull {
            it.name.equals("${apkAsset.name}.sha256", ignoreCase = true) ||
                it.name.endsWith(".sha256", ignoreCase = true)
        }
        val directSha = extractSha256Hex(apkAsset.digest) ?: extractSha256Hex(dto.body)

        return ReleaseUpdateInfo(
            tagName = dto.tagName.ifBlank { "v$versionName" },
            versionName = versionName,
            title = dto.name.ifBlank { dto.tagName.ifBlank { "v$versionName" } },
            notes = dto.body.trim(),
            apkName = apkAsset.name,
            apkUrl = apkAsset.browserDownloadUrl,
            apkSizeBytes = apkAsset.size,
            sha256 = directSha,
            sha256AssetUrl = shaAsset?.browserDownloadUrl?.ifBlank { null },
            htmlUrl = dto.htmlUrl.ifBlank { FALLBACK_RELEASES_PAGE },
        )
    }

    const val LATEST_RELEASE_API = "https://api.github.com/repos/cheng-yi-cc/Taskra/releases/latest"
    const val FALLBACK_RELEASES_PAGE = "https://github.com/cheng-yi-cc/Taskra/releases"
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
            val result = withContext(Dispatchers.IO) { fetchLatestRelease() }
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
            val result = withContext(Dispatchers.IO) { fetchLatestRelease() }
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

    private fun fetchLatestRelease(): Result<ReleaseUpdateInfo?> = runCatching {
        val conn = openGitHubConnection(ReleaseVersionParser.LATEST_RELEASE_API, acceptJson = true)
        try {
            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    ReleaseVersionParser.parseReleaseJson(body, currentVersionName)
                }
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    // 仓库尚未发布任何 Release 时 GitHub API 返回 404，视为当前已是最新
                    null
                }
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
        if (partFile.exists()) partFile.delete()

        val conn = openGitHubConnection(info.apkUrl, acceptJson = false)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("下载失败（HTTP $code）")
            }
            val totalBytes = conn.contentLengthLong.takeIf { it > 0L } ?: info.apkSizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastEmitTime = 0L

            conn.inputStream.buffered().use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime >= 80L || downloaded == totalBytes) {
                            lastEmitTime = now
                            _state.update {
                                it.copy(downloadState = UpdateDownloadState.Downloading(downloaded, totalBytes))
                            }
                        }
                    }
                    output.fd.sync()
                }
            }

            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!expectedSha256.isNullOrBlank() && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                partFile.delete()
                throw IOException("安装包 SHA-256 校验未通过，已拦截安装")
            }
            if (!isValidApkFile(partFile)) {
                partFile.delete()
                throw IOException("下载的 APK 文件不完整或已损坏")
            }
            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
            targetFile
        } catch (e: Throwable) {
            if (partFile.exists()) partFile.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchSha256Text(url: String): String? = runCatching {
        val conn = openGitHubConnection(url, acceptJson = false)
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
            val buf = ByteArray(16 * 1024)
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

    private fun openGitHubConnection(urlString: String, acceptJson: Boolean): HttpURLConnection {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Taskra-Android/$currentVersionName")
                if (acceptJson) {
                    setRequestProperty("Accept", "application/vnd.github+json")
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
}

@Composable
fun UpdateDialogHost(updater: AppUpdater) {
    val state by updater.state.collectAsState()
    val info = state.availableUpdate
    if (!state.showDialog || info == null) return

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
                    .verticalScroll(rememberScrollState()),
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

                when (dl) {
                    is UpdateDownloadState.Downloading -> {
                        Spacer(Modifier.height(4.dp))
                        val p = dl.progress
                        if (p != null) {
                            LinearProgressIndicator(
                                progress = { p },
                                modifier = Modifier.fillMaxWidth().testTag("update_progress"),
                            )
                            Text(
                                "正在下载并校验：${(p * 100).toInt()}%（${formatBytes(dl.downloadedBytes)} / ${formatBytes(dl.totalBytes)}）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("update_progress"))
                            Text(
                                "正在下载并校验：已下载 ${formatBytes(dl.downloadedBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                            dl.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    UpdateDownloadState.Idle -> Unit
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
