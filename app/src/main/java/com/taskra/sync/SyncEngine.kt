package com.taskra.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.taskra.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal data class SyncRunResult(
    val pushed: Int,
    val received: Int,
    val applied: Int,
    val pending: Boolean,
    val retryable: Boolean,
    val blocked: Set<ApplyResult>,
)
private data class OutboxRow(val kind: String, val localId: String, val sequence: Long, val payload: String)
private data class DirtyRow(val kind: String, val localId: String)
private data class InboxRow(val cursor: Long, val record: RemoteRecord)

/** 一次同步；调用方负责串行调用、重试和设备时钟采样。 */
internal class SyncEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val deviceId: String,
    private val transport: SyncTransport,
    private val preferences: SyncPreferences? = null,
    private val attachmentProgress: (Int, Int) -> Unit = { _, _ -> },
) {
    private val pendingDir = File(context.filesDir, "sync-outbox/blobs")
    private val incomingDir = File(context.filesDir, "sync-incoming/blobs")
    private val pendingBlobs = PendingBlobStore(pendingDir)
    private val incomingBlobs = PendingBlobStore(incomingDir)
    private val snapshotter = SyncSnapshotter(context, db, pendingBlobs)
    private val applier = SyncInboxApplier(db, IncomingAssets(context, db, incomingBlobs))

    private suspend fun outbox(): OutboxRow? = db.withTransaction {
        db.openHelper.writableDatabase.query(
            "SELECT kind,record_id,sequence,payload FROM sync_outbox ORDER BY sequence LIMIT 1"
        ).use { if (it.moveToFirst()) OutboxRow(it.getString(0), it.getString(1), it.getLong(2), it.getString(3)) else null }
    }

    private suspend fun dirty(): DirtyRow? = db.withTransaction {
        db.openHelper.writableDatabase.query(
            "SELECT kind,record_id FROM sync_dirty ORDER BY sequence LIMIT 1"
        ).use { if (it.moveToFirst()) DirtyRow(it.getString(0), it.getString(1)) else null }
    }

    private suspend fun cursor(): Long = db.withTransaction {
        db.openHelper.writableDatabase.query("SELECT binding,pull_cursor,enabled FROM sync_control WHERE id=1").use {
            check(it.moveToFirst() && it.getString(0) == deviceId && it.getInt(2) == 1) { "同步设备未初始化" }
            it.getLong(1)
        }
    }

    private suspend fun inbox(): List<InboxRow> = db.withTransaction {
        val rows = mutableListOf<InboxRow>()
        db.openHelper.writableDatabase.query("""
            SELECT a.cursor,a.head_json FROM sync_inbox a
            WHERE a.cursor=(SELECT MAX(b.cursor) FROM sync_inbox b WHERE b.kind=a.kind AND b.record_id=a.record_id)
        """).use { cursor ->
            while (cursor.moveToNext()) rows += InboxRow(cursor.getLong(0), decodeRemoteRecord(cursor.getString(1)))
        }
        val order = listOf("semester", "image", "course", "homework", "draft", "review", "preferences")
        rows.sortedWith(compareBy<InboxRow>({ if (it.record.value == null) 1 else 0 }, {
            val index = order.indexOf(it.record.kind).takeIf { n -> n >= 0 } ?: order.size
            if (it.record.value == null) -index else index
        }, { it.cursor }))
    }

    private fun attachmentHashes(record: RemoteRecord): List<String> {
        val value = record.value ?: return emptyList()
        val wrapped = value as? JsonObject ?: error("远端记录格式无效")
        val list = wrapped["attachments"] as? JsonArray ?: error("远端附件清单无效")
        val hashes = list.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: error("远端附件标识无效") }
        require(hashes.distinct().size == hashes.size && hashes.all { Regex("[a-f0-9]{64}").matches(it) })
        return hashes
    }

    private suspend fun ensureDownloaded(hash: String) {
        if (runCatching { incomingBlobs.get(hash) }.isSuccess) return
        val destination = File(incomingDir, hash)
        if (destination.exists()) {
            // 保留异常副本供排查，再重新下载；不覆盖任何业务附件。
            check(destination.renameTo(File(incomingDir, "$hash.corrupt-${UUID.randomUUID()}"))) { "无法保留损坏的暂存附件" }
        }
        // 设备刚上传的附件已经在持久发件箱中按哈希保存，无需再次走公网下载。
        val alreadySent = runCatching { pendingBlobs.get(hash) }.getOrNull()
        if (alreadySent != null) {
            check(incomingBlobs.stage(alreadySent).first == hash) { "同步附件校验失败" }
            return
        }
        transport.download(hash, destination)
        incomingBlobs.get(hash)
    }

    private fun locallyAvailable(hash: String): Boolean =
        runCatching { incomingBlobs.get(hash) }.isSuccess || runCatching { pendingBlobs.get(hash) }.isSuccess

    /** 先应用附件已就绪的记录；缺附件的记录仍留在收件箱。 */
    private suspend fun applyReady(): Pair<Int, Set<ApplyResult>> {
        var applied = 0
        var blocked = emptySet<ApplyResult>()
        for (pass in 0 until 20) {
            val rows = inbox()
            if (rows.isEmpty()) break
            var progress = false
            val waiting = mutableSetOf<ApplyResult>()
            for (row in rows) {
                val hashes = attachmentHashes(row.record)
                if (hashes.any { !locallyAvailable(it) }) {
                    waiting += ApplyResult.WAITING_ASSETS
                    continue
                }
                hashes.forEach { ensureDownloaded(it) }
                val result = if (row.record.kind == "preferences") preferences?.apply(row.cursor) ?: ApplyResult.WAITING_DEPENDENCY
                    else applier.apply(row.cursor, deviceId)
                when (result) {
                    ApplyResult.APPLIED -> { applied++; progress = true }
                    ApplyResult.SUPERSEDED, ApplyResult.MISSING -> progress = true
                    else -> waiting += result
                }
            }
            blocked = waiting
            if (!progress) break
        }
        return applied to blocked
    }

    suspend fun runOnce(assess: (Long) -> AssessedTime): SyncRunResult = withContext(Dispatchers.IO) {
        var pushed = 0
        var received = 0
        var applied = 0
        var hasMore = false
        var networkInterrupted = false
        var blocked = emptySet<ApplyResult>()
        cursor() // 先检查设备绑定，不能用另一台设备的待发送数据。

        applyReady().also { applied += it.first; blocked = it.second }

        for (iteration in 0 until 5000) {
            val waiting = outbox()
            if (waiting != null) {
                val change = decodeOutgoingChange(waiting.payload)
                val expectedId = if (waiting.kind == "draft") cloudDraftId(waiting.localId, deviceId) else waiting.localId
                check(change.kind == waiting.kind && change.key == expectedId && change.sequence == waiting.sequence) { "待同步记录不一致" }
                change.attachments.forEach { hash -> transport.upload(hash, pendingBlobs.get(hash)) }
                try {
                    transport.push(listOf(change))
                } catch (e: SyncRequestException) {
                    val canDiscard = e.status == 409 && change.ifAbsent && db.withTransaction {
                        SyncJournalSchema.discardRejectedInitialIfChanged(db.openHelper.writableDatabase, waiting.kind, waiting.localId, waiting.sequence)
                    }
                    if (canDiscard) continue
                    throw e
                }
                db.withTransaction {
                    SyncJournalSchema.acknowledge(db.openHelper.writableDatabase, waiting.kind, waiting.localId, waiting.sequence)
                }
                pushed++
            } else {
                val next = dirty() ?: break
                if (!snapshotter.freeze(next.kind, next.localId, deviceId, assess)) { hasMore = true; break }
            }
            if (iteration == 4999) hasMore = true
        }

        // 上传响应中的全局游标不能用于拉取：其中可能包含其他设备的修改。
        var after = cursor()
        for (page in 0 until 100) {
            val pageResult = try { transport.pullPage(after) } catch (_: IOException) {
                networkInterrupted = true
                break
            }
            val changes = pageResult.changes
            if (changes.isEmpty()) {
                if (pageResult.hasMore == true) hasMore = true
                break
            }
            check(db.withTransaction { SyncJournalSchema.enqueueInbox(db.openHelper.writableDatabase, deviceId, after, changes) })
            after = changes.last().cursor
            received += changes.size
            applyReady().also { applied += it.first; blocked = it.second }
            if (pageResult.hasMore == false) break
            if (page == 99) hasMore = true
        }

        val missing = inbox().flatMap { attachmentHashes(it.record) }.distinct().filterNot(::locallyAvailable)
        if (missing.isNotEmpty() && !networkInterrupted) {
            val completed = AtomicInteger(0)
            attachmentProgress(0, missing.size)
            for (batch in missing.chunked(4)) {
                val outcomes = coroutineScope {
                    batch.map { hash -> async(Dispatchers.IO) {
                        try {
                            ensureDownloaded(hash)
                            attachmentProgress(completed.incrementAndGet(), missing.size)
                            true
                        } catch (_: IOException) { false }
                    } }.awaitAll()
                }
                applyReady().also { applied += it.first; blocked = it.second }
                if (outcomes.any { !it }) {
                    networkInterrupted = true
                    break
                }
            }
        }
        applyReady().also { applied += it.first; blocked = it.second }
        val retryable = !networkInterrupted && (hasMore || dirty() != null || outbox() != null)
        SyncRunResult(pushed, received, applied, networkInterrupted || retryable || inbox().isNotEmpty(), retryable, blocked)
    }
}
