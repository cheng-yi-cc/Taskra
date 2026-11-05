package com.taskra.sync

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.taskra.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File

@Serializable
internal data class SyncAsset(val kind: String, val id: String, val hash: String, val image: ImageAsset? = null)

@Serializable
internal data class SyncPayload(val document: JsonElement, val assets: List<SyncAsset> = emptyList())

@Serializable
internal data class ReviewDocument(val session: ReviewSession, val items: List<ReviewItem>)

private data class AssetSource(val kind: String, val id: String, val file: File, val image: ImageAsset? = null)
private data class CapturedChange(
    val kind: String,
    val localId: String,
    val modifiedAt: Long,
    val sequence: Long,
    val databaseSequence: Long,
    val ifAbsent: Boolean,
    val document: JsonElement?,
    val assets: List<AssetSource>,
)

private val SnapshotJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** 先读取事务快照、持久化附件，最后仅在数据库未变化时冻结可重试请求。 */
internal class SyncSnapshotter(
    private val context: Context,
    private val db: AppDatabase,
    private val blobs: PendingBlobStore,
) {
    private fun strictBlocks(raw: String): List<BlockJson> =
        if (raw.isBlank()) emptyList() else BlockJsonCodec.decodeFromString(raw)

    private suspend fun capture(kind: String, id: String): CapturedChange? = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        val row = sql.query(SimpleSQLiteQuery(
            """SELECT modified_at, sequence, deleted, EXISTS(
               SELECT 1 FROM sync_initial WHERE sync_initial.kind=sync_dirty.kind AND sync_initial.record_id=sync_dirty.record_id
               ) FROM sync_dirty WHERE kind=? AND record_id=?""", arrayOf(kind, id),
        )).use { cursor ->
            if (!cursor.moveToFirst()) return@withTransaction null
            listOf(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3))
        }
        val images = linkedSetOf<String>()
        val audio = linkedSetOf<String>()
        fun include(blocks: List<BlockJson>) {
            blocks.forEach {
                if (it.kind == BlockKind.IMAGE) require(!it.imageId.isNullOrBlank()) { "图片内容缺少标识" }
                if (it.kind == BlockKind.AUDIO) require(!it.audioId.isNullOrBlank()) { "录音内容缺少标识" }
                it.imageId?.let(images::add)
                it.audioId?.let(audio::add)
            }
        }
        if (row[2] != 0L) {
            val exists = when (kind) {
                "semester" -> db.semesterDao().get(id) != null
                "course" -> db.courseDao().get(id) != null
                "homework" -> db.homeworkDao().get(id) != null
                "draft" -> db.draftDao().get(id) != null
                "image" -> db.imageDao().get(id) != null
                "review" -> db.reviewDao().getSession(id) != null
                else -> error("不支持的同步记录类型")
            }
            require(!exists) { "删除记录与本地数据不一致" }
        }
        val document = if (row[2] != 0L) null else when (kind) {
            "semester" -> SnapshotJson.encodeToJsonElement(requireNotNull(db.semesterDao().get(id)))
            "course" -> {
                val course = requireNotNull(db.courseDao().get(id))
                course.templateJson?.let { include(BlockJsonCodec.decodeFromString<CourseTemplate>(it).blocks) }
                SnapshotJson.encodeToJsonElement(course)
            }
            "homework" -> {
                val homework = requireNotNull(db.homeworkDao().get(id))
                val blocks = db.blockDao().listByHomework(id)
                val group = homeworkDocuments(listOf(homework), blocks).getValue(id)
                blocks.forEach {
                    if (it.kind == BlockKind.IMAGE) require(!it.imageId.isNullOrBlank()) { "图片内容缺少标识" }
                    if (it.kind == BlockKind.AUDIO) require(!it.audioId.isNullOrBlank()) { "录音内容缺少标识" }
                    it.imageId?.let(images::add)
                    it.audioId?.let(audio::add)
                }
                SnapshotJson.encodeToJsonElement(group)
            }
            "draft" -> {
                val draft = requireNotNull(db.draftDao().get(id))
                include(strictBlocks(draft.blocksJson) + strictBlocks(draft.solutionJson))
                SnapshotJson.encodeToJsonElement(draft)
            }
            "image" -> {
                val image = requireNotNull(db.imageDao().get(id))
                images += id
                SnapshotJson.encodeToJsonElement(image)
            }
            "review" -> {
                val session = requireNotNull(db.reviewDao().getSession(id))
                SnapshotJson.encodeToJsonElement(ReviewDocument(session, db.reviewDao().listItems(id)))
            }
            "preferences" -> {
                require(id == "settings")
                val raw = sql.query("SELECT snapshot FROM sync_preferences WHERE id=1").use {
                    check(it.moveToFirst()); it.getString(0)
                }
                SnapshotJson.parseToJsonElement(raw)
            }
            else -> error("不支持的同步记录类型")
        }
        val assetSources = images.map { imageId ->
            require(Regex("[A-Za-z0-9_-]+").matches(imageId)) { "图片标识无效" }
            val image = requireNotNull(db.imageDao().get(imageId)) { "图片记录缺失" }
            require(image.relativePath == "images/$imageId.jpg") { "图片路径无效" }
            AssetSource("image", imageId, File(context.filesDir, image.relativePath), image)
        } + audio.map { audioId ->
            AssetSource("audio", audioId, audioFile(context, audioId))
        }
        CapturedChange(kind, id, row[0], row[1], SyncJournalSchema.databaseSequence(sql), row[3] != 0L, document, assetSources)
    }

    suspend fun freeze(
        kind: String,
        localId: String,
        deviceId: String,
        assess: (Long) -> AssessedTime,
    ): Boolean = withContext(Dispatchers.IO) {
        SyncGates.operation.withLock {
        val captured = capture(kind, localId) ?: return@withLock false
        val assets = captured.assets.map { source ->
            val (hash, file) = blobs.stage(source.file)
            source.image?.let {
                require(it.sizeBytes == 0L || it.sizeBytes == file.length()) { "图片大小与记录不一致" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片文件损坏" }
            }
            SyncAsset(source.kind, source.id, hash, source.image)
        }
        val assessed = assess(captured.modifiedAt)
        val payload = captured.document?.let { SnapshotJson.encodeToJsonElement(SyncPayload(it, assets)) }
        val cloudId = if (kind == "draft") cloudDraftId(localId, deviceId) else localId
        val outgoing = OutgoingChange(
            kind, cloudId, assessed.modifiedAt, captured.sequence, payload, assessed.reliable,
            assets.map { it.hash }.distinct(), captured.ifAbsent,
        )
        db.withTransaction {
            SyncJournalSchema.freeze(db.openHelper.writableDatabase, kind, localId, captured.sequence,
                captured.databaseSequence, encodeOutgoingChange(outgoing))
        }
        }
    }
}
