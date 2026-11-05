package com.taskra.sync

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.taskra.data.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Serializable
private data class ServerValue(val payload: SyncPayload, val attachments: List<String>)

internal enum class ApplyResult { APPLIED, WAITING_LOCAL, WAITING_DEPENDENCY, WAITING_ASSETS, WAITING_CONFLICT, SUPERSEDED, MISSING }

private class MissingDependency : Exception()
private val IncomingJson = Json { ignoreUnknownKeys = false }

/**
 * 含附件的记录在附件安全安装完成前不应用。
 */
internal class SyncInboxApplier(private val db: AppDatabase, private val assets: IncomingAssets? = null) {
    suspend fun apply(cursor: Long, localDeviceId: String): ApplyResult = withContext(Dispatchers.IO) {
        SyncGates.operation.withLock {
        val initial = db.withTransaction {
            db.openHelper.writableDatabase.query(SimpleSQLiteQuery(
                "SELECT head_json FROM sync_inbox WHERE cursor=?", arrayOf(cursor),
            )).use { if (it.moveToFirst()) it.getString(0) else null }
        } ?: return@withLock ApplyResult.MISSING
        val initialHead = decodeRemoteRecord(initial)
        val initialValue = initialHead.value?.let { IncomingJson.decodeFromJsonElement<ServerValue>(it) }
        if (initialValue != null) {
            require(initialValue.attachments.distinct().size == initialValue.attachments.size)
            require(initialValue.attachments.toSet() == initialValue.payload.assets.map { it.hash }.toSet()) { "附件清单不一致" }
            require(documentReferences(initialHead.kind, initialHead.key, initialValue.payload.document) ==
                initialValue.payload.assets.map { it.kind to it.id }.toSet()) { "记录和附件引用不一致" }
        }
        val prepared = if (initialValue != null && initialValue.payload.assets.isNotEmpty()) {
            val installer = assets ?: return@withLock ApplyResult.WAITING_ASSETS
            installer.prepare(initialValue.payload.assets) ?: return@withLock ApplyResult.WAITING_ASSETS
        } else emptyList()
        try {
        SyncGates.editor.withLock { try {
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            val saved = sql.query(SimpleSQLiteQuery("SELECT kind, record_id, head_json FROM sync_inbox WHERE cursor=?", arrayOf(cursor))).use {
                if (!it.moveToFirst()) return@withTransaction ApplyResult.MISSING
                Triple(it.getString(0), it.getString(1), it.getString(2))
            }
            val binding = sql.query("SELECT binding FROM sync_control WHERE id=1").use { check(it.moveToFirst()); it.getString(0) }
            check(binding == localDeviceId && binding.isNotBlank()) { "同步设备绑定不匹配" }
            val head = decodeRemoteRecord(saved.third)
            check(saved.third == initial) { "远端记录已变化" }
            require(head.kind == saved.first && head.key == saved.second) { "远端记录标识不一致" }
            val localId = if (head.kind == "draft") localDraftId(head.key, localDeviceId) else head.key
            if (SyncJournalSchema.hasLocalPending(sql, head.kind, localId)) return@withTransaction ApplyResult.WAITING_LOCAL
            if (initialValue?.payload?.assets.orEmpty().any {
                    it.kind == "image" && SyncJournalSchema.hasLocalPending(sql, "image", it.id)
                }) return@withTransaction ApplyResult.WAITING_LOCAL
            val latest = sql.query(SimpleSQLiteQuery(
                "SELECT MAX(cursor) FROM sync_inbox WHERE kind=? AND record_id=?", arrayOf(head.kind, head.key),
            )).use { check(it.moveToFirst()); it.getLong(0) }
            if (latest != cursor) return@withTransaction ApplyResult.SUPERSEDED

            val value = initialValue
            SyncGates.assets.withLock {
            sql.execSQL("UPDATE sync_control SET applying_remote=1 WHERE id=1")
            if (prepared.isNotEmpty()) requireNotNull(assets).install(prepared)
            if (value == null) delete(head.kind, localId) else applyDocument(head.kind, head.key, localId, value.payload)
            SyncJournalSchema.consumeInbox(sql, head.kind, head.key, localId, cursor)
            sql.execSQL("UPDATE sync_control SET applying_remote=0 WHERE id=1")
            }
            ApplyResult.APPLIED
        }
    } catch (_: MissingDependency) {
        ApplyResult.WAITING_DEPENDENCY
    } catch (_: AssetConflict) {
        ApplyResult.WAITING_CONFLICT
    } }
        } finally { prepared.forEach { it.temporary.delete() } }
        }
    }

    private fun documentReferences(kind: String, id: String, document: JsonElement): Set<Pair<String, String>> {
        val result = mutableSetOf<Pair<String, String>>()
        fun block(kind: String, image: String?, audio: String?) {
            require(kind in setOf(BlockKind.TEXT, BlockKind.IMAGE, BlockKind.AUDIO))
            if (kind == BlockKind.IMAGE) require(!image.isNullOrBlank())
            if (kind == BlockKind.AUDIO) require(!audio.isNullOrBlank())
            image?.let { result += "image" to it }
            audio?.let { result += "audio" to it }
        }
        when (kind) {
            "homework" -> {
                val value = IncomingJson.decodeFromJsonElement<HomeworkDocument>(document)
                require(value.homework.id == id)
                homeworkDocuments(listOf(value.homework), value.blocks)
                value.blocks.forEach { block(it.kind, it.imageId, it.audioId) }
            }
            "course" -> IncomingJson.decodeFromJsonElement<Course>(document).templateJson?.let {
                BlockJsonCodec.decodeFromString<CourseTemplate>(it).blocks.forEach { b -> block(b.kind, b.imageId, b.audioId) }
            }
            "draft" -> IncomingJson.decodeFromJsonElement<EditorDraft>(document).let {
                (strictBlocks(it.blocksJson) + strictBlocks(it.solutionJson)).forEach { b -> block(b.kind, b.imageId, b.audioId) }
            }
            "image" -> {
                val image = IncomingJson.decodeFromJsonElement<ImageAsset>(document)
                require(image.id == id)
                result += "image" to id
            }
            "semester", "review" -> Unit
            else -> error("不支持的同步记录类型")
        }
        return result
    }

    private fun strictBlocks(raw: String): List<BlockJson> = if (raw.isBlank()) emptyList() else BlockJsonCodec.decodeFromString(raw)
    private fun need(condition: Boolean) { if (!condition) throw MissingDependency() }

    private suspend fun applyDocument(kind: String, cloudId: String, localId: String, payload: SyncPayload) {
        when (kind) {
            "semester" -> {
                val value = IncomingJson.decodeFromJsonElement<Semester>(payload.document)
                require(value.id == cloudId)
                db.semesterDao().upsert(value)
            }
            "course" -> {
                val value = IncomingJson.decodeFromJsonElement<Course>(payload.document)
                require(value.id == cloudId)
                need(db.semesterDao().get(value.semesterId) != null)
                val duplicate = db.courseDao().findByName(value.semesterId, value.name)
                need(duplicate == null || duplicate.id == value.id)
                db.courseDao().upsert(value)
            }
            "homework" -> {
                val value = IncomingJson.decodeFromJsonElement<HomeworkDocument>(payload.document)
                require(value.homework.id == cloudId)
                require(value.homework.status == Status.TODO || value.homework.status == Status.DONE)
                val normalized = homeworkDocuments(listOf(value.homework), value.blocks).getValue(cloudId)
                require(normalized.blocks.all { it.kind in setOf(BlockKind.TEXT, BlockKind.IMAGE, BlockKind.AUDIO) })
                need(db.semesterDao().get(value.homework.semesterId) != null)
                value.homework.courseId?.let { need(db.courseDao().get(it) != null) }
                db.homeworkDao().upsert(value.homework)
                db.blockDao().deleteByHomework(localId)
                if (normalized.blocks.isNotEmpty()) db.blockDao().upsertAll(normalized.blocks)
            }
            "draft" -> {
                val value = IncomingJson.decodeFromJsonElement<EditorDraft>(payload.document)
                require(value.id == cloudId || (cloudId.startsWith("new:") && value.id == "new"))
                value.semesterId?.let { need(db.semesterDao().get(it) != null) }
                value.courseId?.let { need(db.courseDao().get(it) != null) }
                value.homeworkId?.let { need(db.homeworkDao().get(it) != null) }
                // 云端的 new:<设备> 映射为拥有者本机的 new，不采用对方传入的本地草稿键。
                db.draftDao().upsert(value.copy(id = localId))
            }
            "review" -> {
                val value = IncomingJson.decodeFromJsonElement<ReviewDocument>(payload.document)
                require(value.session.id == cloudId && value.items.all { it.sessionId == cloudId })
                require(value.items.map { it.homeworkId }.toSet().size == value.items.size)
                need(db.courseDao().get(value.session.courseId) != null)
                db.reviewDao().upsertSession(value.session)
                db.reviewDao().deleteItemsBySession(localId)
                if (value.items.isNotEmpty()) db.reviewDao().upsertItems(value.items)
            }
            "image" -> {
                val image = IncomingJson.decodeFromJsonElement<ImageAsset>(payload.document)
                require(image.id == cloudId && payload.assets.single().image == image)
                db.imageDao().upsert(image)
            }
            else -> error("不支持的同步记录类型")
        }
    }

    private suspend fun delete(kind: String, id: String) {
        val sql = db.openHelper.writableDatabase
        when (kind) {
            "homework" -> {
                val hasDraft = sql.query(SimpleSQLiteQuery("SELECT 1 FROM editor_drafts WHERE homeworkId=? LIMIT 1", arrayOf(id))).use { it.moveToFirst() }
                need(!hasDraft)
                db.homeworkDao().deleteHard(id)
            }
            "draft" -> db.draftDao().delete(id)
            "review" -> db.reviewDao().deleteSession(id)
            "course" -> {
                val referenced = sql.query(SimpleSQLiteQuery(
                    """SELECT 1 FROM homework WHERE courseId=? UNION ALL SELECT 1 FROM editor_drafts WHERE courseId=?
                       UNION ALL SELECT 1 FROM review_sessions WHERE courseId=? LIMIT 1""", arrayOf(id, id, id),
                )).use { it.moveToFirst() }
                need(!referenced)
                db.courseDao().deleteHard(id)
            }
            "semester" -> {
                val referenced = sql.query(SimpleSQLiteQuery(
                    """SELECT 1 FROM courses WHERE semesterId=? UNION ALL SELECT 1 FROM homework WHERE semesterId=?
                       UNION ALL SELECT 1 FROM editor_drafts WHERE semesterId=? LIMIT 1""", arrayOf(id, id, id),
                )).use { it.moveToFirst() }
                need(!referenced)
                db.semesterDao().delete(id)
            }
            "image" -> {
                val drafts = db.draftDao().listAll().flatMap { strictBlocks(it.blocksJson) + strictBlocks(it.solutionJson) }
                val templates = db.courseDao().listAll().flatMap {
                    it.templateJson?.let { raw -> BlockJsonCodec.decodeFromString<CourseTemplate>(raw).blocks }.orEmpty()
                }
                val referenced = db.blockDao().allReferencedImageIds().toSet() + drafts.mapNotNull { it.imageId } + templates.mapNotNull { it.imageId }
                need(id !in referenced)
                db.imageDao().delete(id) // 文件暂留，避免删除其他设备仍需的历史附件
            }
            else -> error("不支持的同步记录类型")
        }
    }
}
