package com.taskra.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.taskra.data.AppDatabase
import com.taskra.data.PrefsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class SyncUiState(val paired: Boolean = false, val busy: Boolean = false, val message: String = "尚未连接")

/** 进程运行期间监听数据与网络；进程结束后下次打开应用继续同步。 */
internal class SyncCoordinator(private val context: Context, private val db: AppDatabase) {
    private val credentials = SyncCredentialStore(context)
    private val pairingAttempt = SyncCredentialStore(context, "sync_pairing_attempt")
    private val clock = ClockSampleStore(context)
    private val lanHints = SyncLanHints(context)
    private val prefs = PrefsRepository(context)
    private val syncPreferences = SyncPreferences(db, prefs)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val runMutex = Mutex()
    private val mutableState = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = mutableState

    private val observer = object : InvalidationTracker.Observer(
        "semesters", "courses", "homework", "content_blocks", "image_assets", "editor_drafts", "review_sessions", "review_items",
    ) {
        override fun onInvalidated(tables: Set<String>) { requestSync() }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { requestSync() }
    }

    init {
        db.invalidationTracker.addObserver(observer)
        runCatching { context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback) }
        scope.launch {
            for (ignored in wakeups) {
                delay(800)
                try { syncNow() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* 状态由 syncNow 更新 */ }
            }
        }
        // 对端的变更不会触发本机 Room 通知，前台进程存活时定期拉取并重试暂时失败的请求。
        scope.launch {
            while (true) {
                delay(30_000)
                requestSync()
            }
        }
        scope.launch { prefs.prefs.drop(1).collect { requestSync() } }
        scope.launch {
            try {
                val pending = pairingAttempt.load()
                if (pending != null && credentials.load() == null) connect(pending.endpoint, pending.token)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* 留待用户重新输入配对码 */ }
        }
        requestSync()
    }

    fun requestSync() { wakeups.trySend(Unit) }

    /** 注销监听用于受控关闭；生产进程内此对象与 Application 同生命周期。 */
    fun close() {
        db.invalidationTracker.removeObserver(observer)
        runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        scope.cancel()
    }

    private suspend fun ensureBound(value: SyncCredentials) {
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            val state = sql.query("SELECT enabled,binding,bootstrap_complete FROM sync_control WHERE id=1").use {
                check(it.moveToFirst()); Triple(it.getInt(0), it.getString(1), it.getInt(2))
            }
            if (state.first == 0 && state.third == 0) {
                SyncJournalSchema.bindDevice(sql, value.deviceId)
                // 首次加入时本地旧数据仅能创建服务器上不存在的记录，不能覆盖既有记录。
                SyncJournalSchema.bootstrap(sql, onlyIfAbsent = true)
            } else {
                check(state.first == 1 && state.second == value.deviceId && state.third == 1) { "同步设备绑定不匹配" }
            }
        }
    }

    suspend fun connect(endpoint: String, code: String) {
        runMutex.withLock {
            check(credentials.load() == null) { "此设备已经连接" }
            val binding = db.withTransaction {
                db.openHelper.writableDatabase.query("SELECT binding FROM sync_control WHERE id=1").use {
                    check(it.moveToFirst()); it.getString(0)
                }
            }
            check(binding.isEmpty()) { "旧同步绑定需要先安全恢复" }
            mutableState.value = SyncUiState(busy = true, message = "正在连接…")
            try {
                val address = endpoint.trim().trimEnd('/')
                val client = SyncClient(address)
                val normalizedCode = code.trim()
                val oldAttempt = pairingAttempt.load()
                val attempt = if (oldAttempt?.endpoint == address && oldAttempt.token == normalizedCode) oldAttempt
                    else SyncCredentials(address, UUID.randomUUID().toString(), normalizedCode).also { pairingAttempt.save(it) }
                val paired = client.redeem(normalizedCode, attempt.deviceId)
                val value = SyncCredentials(address, paired.deviceId, paired.token)
                credentials.save(value)
                pairingAttempt.clear()
                ensureBound(value)
                mutableState.value = SyncUiState(paired = true, message = "已连接，等待同步")
            } catch (e: Exception) {
                // 配对码可能已经兑换成功；凭据落盘后绝不能假装仍未连接并要求再次兑换。
                val persisted = runCatching { credentials.load() != null }.getOrDefault(false)
                mutableState.value = if (persisted) SyncUiState(paired = true, message = "已连接，初始化待重试")
                    else SyncUiState(message = "连接失败，请检查配对码和网络")
                if (persisted) requestSync()
                throw e
            }
        }
        requestSync()
    }

    suspend fun syncNow(): SyncRunResult? = runMutex.withLock {
        var paired = false
        try {
            val stored = credentials.load()
            if (stored == null) {
                mutableState.value = SyncUiState()
                return@withLock null
            }
            val value = upgradedSyncEndpoint(stored) { candidate ->
                SyncClient(candidate.endpoint, candidate.token).serverTime()
            }
            if (value != stored) credentials.save(value)
            paired = true
            ensureBound(value)
            mutableState.value = SyncUiState(paired = true, busy = true, message = "正在同步…")
            val previous = clock.load()
            val boot = clock.bootId()
            data class Sample(val wallBefore: Long, val wallAfter: Long, val elapsedBefore: Long, val elapsedAfter: Long, val serverTime: Long)
            suspend fun sample(client: SyncClient): Sample {
                val wallBefore = System.currentTimeMillis()
                val elapsedBefore = SystemClock.elapsedRealtime()
                val serverTime = client.serverTime()
                return Sample(wallBefore, System.currentTimeMillis(), elapsedBefore, SystemClock.elapsedRealtime(), serverTime)
            }
            val defaultServer = value.endpoint.trimEnd('/') == DEFAULT_SYNC_ENDPOINT
            val uploadProgress: (Long, Long) -> Unit = { done, total ->
                val percent = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
                mutableState.value = SyncUiState(paired = true, busy = true, message = "正在上传附件 $percent%")
            }
            val wan = SyncClient(value.endpoint, value.token, onLanAddress = { if (defaultServer) lanHints.remember(it) }, onUploadProgress = uploadProgress)
            fun lanClient(): SyncClient? {
                val address = if (defaultServer) lanHints.matchingWifiAddress() else null
                return address?.let { SyncClient("https://$LAN_TLS_HOST:$LAN_SYNC_PORT/taskra-sync", value.token, lanProxyAddress = it, onUploadProgress = uploadProgress) }
            }
            suspend fun probeLan(candidate: SyncClient?): Pair<SyncClient, Sample>? {
                if (candidate == null) return null
                return try { candidate to sample(candidate) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }
            val initialLan = lanClient()
            var local = probeLan(initialLan)
            if (local == null) {
                val internetSample = sample(wan)
                local = if (initialLan == null) probeLan(lanClient()) else null
                if (local == null) local = wan to internetSample
            }
            val (client, measured) = requireNotNull(local)
            val usingLan = client !== wan
            syncPreferences.reconcile(if (syncPreferences.needsInitialization()) client.hasRemoteData() else true)
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val engine = SyncEngine(context, db, value.deviceId, client, syncPreferences) { done, total ->
                mutableState.value = SyncUiState(paired = true, busy = true, message = "正在同步附件 $done/$total")
            }
            val result = engine.runOnce {
                assessChangeTime(it, previous, nowWall, nowElapsed, boot)
            }
            clock.save(ClockSample(measured.wallBefore, measured.wallAfter, measured.elapsedBefore, measured.elapsedAfter, measured.serverTime, boot))
            val message = when {
                ApplyResult.WAITING_CONFLICT in result.blocked -> "附件冲突，已保留本机文件"
                ApplyResult.WAITING_DEPENDENCY in result.blocked -> "部分记录等待关联数据"
                result.pending -> "有数据等待同步"
                else -> if (usingLan) "局域网同步完成" else "同步完成"
            }
            mutableState.value = SyncUiState(paired = true, message = message)
            if (result.retryable) requestSync()
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = if (e is SyncRequestException && e.status == 409)
                "本机旧数据与服务器已有记录冲突，已保留本机数据"
            else "同步未完成，请检查网络或数据后重试"
            mutableState.value = SyncUiState(paired = paired, message = message)
            throw e
        }
    }

    suspend fun createPairCode(): PairCode {
        val result = requireNotNull(syncNow()) { "设备尚未连接" }
        check(!result.pending) { "请先完成本机同步" }
        val value = requireNotNull(credentials.load())
        return SyncClient(value.endpoint, value.token).issuePairCode()
    }
}
