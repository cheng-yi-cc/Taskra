package com.taskra.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.taskra.sync.SyncGates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

const val BACKUP_FORMAT_VERSION = 4

@Serializable
data class BackupManifest(
    val formatVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val counts: Map<String, Int> = emptyMap(),
    val sha256: Map<String, String> = emptyMap(), // 路径 -> sha256
)

@Serializable
data class BackupData(
    val semesters: List<Semester>,
    val courses: List<Course>,
    val homework: List<Homework>,
    val blocks: List<ContentBlock>,
    val images: List<ImageAsset>,
    val drafts: List<EditorDraft>,
    val sessions: List<ReviewSession>,
    val items: List<ReviewItem>,
    val prefs: UserPrefs,
    val audioIds: List<String> = emptyList(),
)

data class BackupSummary(
    val createdAt: Long,
    val homeworkCount: Int,
    val courseCount: Int,
    val imageCount: Int,
    val hasDrafts: Boolean,
    val audioCount: Int = 0,
)

private val BackupJson = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

fun sha256(f: File): String {
    val d = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { ins ->
        val buf = ByteArray(8192)
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            d.update(buf, 0, n)
        }
    }
    return d.digest().joinToString("") { "%02x".format(it) }
}

fun sha256Bytes(bytes: ByteArray): String {
    val d = MessageDigest.getInstance("SHA-256")
    d.update(bytes)
    return d.digest().joinToString("") { "%02x".format(it) }
}

class BackupManager(
    private val context: Context,
    private val repo: TaskraRepository,
    private val prefs: PrefsRepository,
) {
    /**
     * 导出期间保持数据库快照及附件引用稳定，缺少附件时明确失败。
     * 成功后才由调用方更新 lastBackupTime。
     */
    suspend fun exportTo(uri: Uri): Unit = withContext(Dispatchers.IO) {
        SyncGates.operation.withLock {
        val db = repo.db
        db.withTransaction {
        SyncGates.assets.withLock {
        val semesters = repo.listSemesters()
        val courses = repo.listCoursesAll()
        val homeworkAll = db.homeworkDao().listActive() + db.homeworkDao().listTrash()
        val blocksAll = mutableListOf<ContentBlock>()
        homeworkAll.forEach { h ->
            blocksAll += db.blockDao().listByHomework(h.id)
        }
        val images = db.imageDao().listAll()
        val draftsAll = db.draftDao().listAll()
        val sessionsAll = db.reviewDao().listAllSessions()
        val itemsAll = db.reviewDao().listAllItems()
        courses.forEach { c ->
            // 历史会话已通过全表读取（含已结束轮次），保留历史
        }
        // 直接从 review_sessions 全表读取（含已结束轮次，保留历史）
        val p = prefs.snapshot()
        fun strictBlocks(raw: String): List<BlockJson> = if (raw.isBlank()) emptyList() else BlockJsonCodec.decodeFromString(raw)
        val auxiliaryBlocks = courses.flatMap { course ->
            course.templateJson?.let { BlockJsonCodec.decodeFromString<CourseTemplate>(it).blocks }.orEmpty()
        } + draftsAll.flatMap { strictBlocks(it.blocksJson) + strictBlocks(it.solutionJson) }
        val imageIds = blocksAll.mapNotNull { it.imageId } + auxiliaryBlocks.mapNotNull { it.imageId }
        require(images.map { it.id }.toSet().containsAll(imageIds)) { "图片记录缺失，无法导出完整备份" }
        val audioIds = (blocksAll.mapNotNull { it.audioId } + auxiliaryBlocks.mapNotNull { it.audioId }).distinct()
        val data = BackupData(semesters, courses, homeworkAll, blocksAll, images, draftsAll, sessionsAll, itemsAll, p, audioIds)
        val dataBytes = BackupJson.encodeToString(data).toByteArray(Charsets.UTF_8)

        val hashes = mutableMapOf<String, String>()
        hashes["data.json"] = sha256Bytes(dataBytes)
        // 图片校验
        images.forEach { img ->
            val f = File(context.filesDir, img.relativePath)
            require(img.relativePath == "images/${img.id}.jpg" && f.isFile && f.length() > 0) { "图片文件缺失：${img.id}" }
            hashes["assets/${img.id}.jpg"] = sha256(f)
        }
        audioIds.forEach { id ->
            val file = audioFile(context, id)
            require(file.isFile && file.length() > 0) { "录音文件缺失：$id" }
            hashes["audio/$id.m4a"] = sha256(file)
        }
        val manifest = BackupManifest(
            formatVersion = BACKUP_FORMAT_VERSION,
            createdAt = System.currentTimeMillis(),
            counts = mapOf(
                "semesters" to semesters.size,
                "courses" to courses.size,
                "homework" to homeworkAll.size,
                "images" to images.size,
                "audio" to audioIds.size,
                "sessions" to sessionsAll.size,
                "drafts" to draftsAll.size,
            ),
            sha256 = hashes,
        )
        val manifestBytes = BackupJson.encodeToString(manifest).toByteArray(Charsets.UTF_8)

        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestBytes)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("data.json"))
                zip.write(dataBytes)
                zip.closeEntry()
                images.forEach { img ->
                    val f = File(context.filesDir, img.relativePath)
                    zip.putNextEntry(ZipEntry("assets/${img.id}.jpg"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                audioIds.forEach { id ->
                    zip.putNextEntry(ZipEntry("audio/$id.m4a"))
                    audioFile(context, id).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: throw IllegalArgumentException("无法打开导出位置")
        }
        }
        }
    }

    /** 读取备份概要（不覆盖数据）。做路径穿越与大小校验。 */
    suspend fun inspectBackup(uri: Uri, maxBytes: Long = 500L * 1024 * 1024): BackupSummary =
        withContext(Dispatchers.IO) {
            val tmp = File(context.cacheDir, "taskra_inspect_${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxBytes) throw IllegalArgumentException("备份文件过大，已停止读取")
                        out.write(buf, 0, n)
                    }
                }
            } ?: throw IllegalArgumentException("无法读取备份文件")
            try {
                ZipFile(tmp).use { zip ->
                    val entries = zip.entries().asSequence().toList()
                    if (entries.size > 5000) throw IllegalArgumentException("备份条目异常")
                    entries.forEach {
                        if (it.name.contains("..") || it.name.startsWith("/")) {
                            throw IllegalArgumentException("备份包含非法路径")
                        }
                    }
                    val manifestEntry = zip.getEntry("manifest.json")
                        ?: throw IllegalArgumentException("缺少 manifest.json")
                    val manifest = BackupJson.decodeFromString<BackupManifest>(
                        zip.getInputStream(manifestEntry).readBytes().toString(Charsets.UTF_8)
                    )
                    if (manifest.formatVersion > BACKUP_FORMAT_VERSION) {
                        throw IllegalArgumentException("备份版本过新，当前应用无法恢复")
                    }
                    val dataEntry = zip.getEntry("data.json")
                        ?: throw IllegalArgumentException("缺少 data.json")
                    val data = BackupJson.decodeFromString<BackupData>(
                        zip.getInputStream(dataEntry).readBytes().toString(Charsets.UTF_8)
                    )
                    BackupSummary(
                        createdAt = manifest.createdAt,
                        homeworkCount = data.homework.size,
                        courseCount = data.courses.size,
                        imageCount = data.images.size,
                        hasDrafts = data.drafts.isNotEmpty(),
                        audioCount = data.audioIds.size,
                    )
                }
            } finally {
                try { tmp.delete() } catch (_: Exception) { }
            }
        }

    /**
     * 覆盖恢复：先完整校验，再生成当前数据回滚副本，失败则回滚。
     * [confirmed] 必须为 true（调用方已展示概要并二次确认）。
     */
    suspend fun restoreFrom(uri: Uri, confirmed: Boolean, imageStore: ImageStore): Unit =
        withContext(Dispatchers.IO) {
            SyncGates.operation.withLock {
            require(confirmed) { "恢复需要二次确认" }
            val tmp = File(context.cacheDir, "taskra_restore_${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { out -> ins.copyTo(out) }
            } ?: throw IllegalArgumentException("无法读取备份文件")
            try {
                ZipFile(tmp).use { zip ->
                    val manifest = BackupJson.decodeFromString<BackupManifest>(
                        zip.getInputStream(zip.getEntry("manifest.json")
                            ?: throw IllegalArgumentException("缺少 manifest.json"))
                            .readBytes().toString(Charsets.UTF_8)
                    )
                    if (manifest.formatVersion !in 1..BACKUP_FORMAT_VERSION) {
                        throw IllegalArgumentException("不支持的备份版本：${manifest.formatVersion}")
                    }
                    val dataBytes = zip.getInputStream(zip.getEntry("data.json")
                        ?: throw IllegalArgumentException("缺少 data.json")).readBytes()
                    if (sha256Bytes(dataBytes) != manifest.sha256["data.json"]) {
                        throw IllegalArgumentException("data.json 校验失败，备份可能已损坏")
                    }
                    val data = BackupJson.decodeFromString<BackupData>(dataBytes.toString(Charsets.UTF_8))
                    // 数据关系校验
                    val semIds = data.semesters.map { it.id }.toSet()
                    data.courses.forEach {
                        require(it.semesterId in semIds) { "课程引用了不存在的学期" }
                    }
                    data.homework.forEach {
                        require(it.semesterId in semIds) { "作业引用了不存在的学期" }
                    }
                    // 图片存在性+校验
                    data.images.forEach { img ->
                        val e = zip.getEntry("assets/${img.id}.jpg")
                            ?: throw IllegalArgumentException("备份缺少图片：${img.id}")
                        if (e.size <= 0) throw IllegalArgumentException("图片为空：${img.id}")
                    }
                    val referencedAudio = data.courses.flatMap { decodeTemplate(it.templateJson)?.blocks.orEmpty() }.mapNotNull { it.audioId } + data.blocks.mapNotNull { it.audioId } + data.drafts.flatMap {
                        (decodeBlocks(it.blocksJson) + decodeBlocks(it.solutionJson)).mapNotNull { block -> block.audioId }
                    }
                    require(data.audioIds.containsAll(referencedAudio)) { "备份缺少录音清单" }
                    performRestore(zip, data, manifest)

                }
            } finally {
                try { tmp.delete() } catch (_: Exception) { }
            }
            }
        }

    private suspend fun performRestore(zip: ZipFile, data: BackupData, manifest: BackupManifest) {
        val db = repo.db
        val staging = File(context.filesDir, "tmp/restore_${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val oldPrefs = prefs.snapshot()
        val installed = mutableListOf<Pair<File, File?>>()
        try {
            val files = mutableListOf<Pair<File, File>>()
            data.images.forEach { img ->
                require(Regex("[a-zA-Z0-9_-]+").matches(img.id)) { "图片标识无效" }
                val path = "assets/${img.id}.jpg"
                val entry = zip.getEntry(path) ?: error("备份缺少图片")
                val staged = File(staging, "image-${img.id}.jpg")
                zip.getInputStream(entry).use { input -> staged.outputStream().use { input.copyTo(it) } }
                manifest.sha256[path]?.let { require(sha256(staged) == it) { "图片校验失败" } }
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(staged.absolutePath, options)
                require(options.outWidth > 0) { "备份图片损坏" }
                files += staged to File(context.filesDir, "images/${img.id}.jpg")
            }
            data.audioIds.distinct().forEach { id ->
                val destination = audioFile(context, id)
                val path = "audio/$id.m4a"
                val entry = zip.getEntry(path) ?: error("备份缺少录音")
                val staged = File(staging, "audio-$id.m4a")
                zip.getInputStream(entry).use { input -> staged.outputStream().use { input.copyTo(it) } }
                require(staged.length() > 0 && sha256(staged) == manifest.sha256[path]) { "录音校验失败" }
                files += staged to destination
            }
            // 所有文件先校验；覆盖文件留回滚副本，DB 失败时恢复原文件。
            files.forEachIndexed { index, (staged, destination) ->
                destination.parentFile?.mkdirs()
                val old = if (destination.exists()) File(staging, "rollback-$index").also { destination.copyTo(it) } else null
                installed += destination to old
                staged.copyTo(destination, overwrite = true)
            }
            db.withTransaction {
                db.clearAllTables()
                data.semesters.forEach { db.semesterDao().upsert(it) }
                data.courses.forEach { db.courseDao().upsert(it) }
                data.homework.forEach { db.homeworkDao().upsert(it) }
                if (data.blocks.isNotEmpty()) db.blockDao().upsertAll(data.blocks)
                data.images.forEach { db.imageDao().upsert(it.copy(relativePath = "images/${it.id}.jpg")) }
                data.drafts.forEach { db.draftDao().upsert(it) }
                data.sessions.forEach { db.reviewDao().upsertSession(it) }
                if (data.items.isNotEmpty()) db.reviewDao().upsertItems(data.items)
                prefs.restore(data.prefs)
            }
        } catch (e: Exception) {
            installed.asReversed().forEach { (destination, old) ->
                if (old != null) old.copyTo(destination, overwrite = true) else destination.delete()
            }
            prefs.restore(oldPrefs)
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }
}
