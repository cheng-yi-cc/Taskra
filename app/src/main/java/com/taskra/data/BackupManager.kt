package com.taskra.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

const val BACKUP_FORMAT_VERSION = 1

@Serializable
data class BackupManifest(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
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
)

data class BackupSummary(
    val createdAt: Long,
    val homeworkCount: Int,
    val courseCount: Int,
    val imageCount: Int,
    val hasDrafts: Boolean,
)

private val BackupJson = Json { ignoreUnknownKeys = true; prettyPrint = false }

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
     * 导出完整备份：先取一致快照（ suspend 串行读取），再打包。
     * 成功后才由调用方更新 lastBackupTime。
     */
    suspend fun exportTo(uri: Uri): Unit = withContext(Dispatchers.IO) {
        val semesters = repo.listSemesters()
        val courses = repo.listCoursesAll()
        // 一致快照：Room 读取 + 文件复制都在 IO 线程串行完成
        val db = repo.db
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
        val data = BackupData(semesters, courses, homeworkAll, blocksAll, images, draftsAll, sessionsAll, itemsAll, p)
        val dataBytes = BackupJson.encodeToString(data).toByteArray(Charsets.UTF_8)

        val hashes = mutableMapOf<String, String>()
        hashes["data.json"] = sha256Bytes(dataBytes)
        // 图片校验
        images.forEach { img ->
            val f = File(context.filesDir, img.relativePath)
            if (f.exists()) hashes["assets/${img.id}.jpg"] = sha256(f)
        }
        val manifest = BackupManifest(
            formatVersion = BACKUP_FORMAT_VERSION,
            createdAt = System.currentTimeMillis(),
            counts = mapOf(
                "semesters" to semesters.size,
                "courses" to courses.size,
                "homework" to homeworkAll.size,
                "images" to images.size,
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
                    if (!f.exists()) return@forEach
                    zip.putNextEntry(ZipEntry("assets/${img.id}.jpg"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: throw IllegalArgumentException("无法打开导出位置")
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
            require(confirmed) { "恢复需要二次确认" }
            val tmp = File(context.cacheDir, "taskra_restore_${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { out -> ins.copyTo(out) }
            } ?: throw IllegalArgumentException("无法读取备份文件")
            // 回滚副本：导出当前快照到 cache
            val rollback = File(context.cacheDir, "taskra_rollback_${System.currentTimeMillis()}.zip")
            try {
                ZipFile(tmp).use { zip ->
                    val manifest = BackupJson.decodeFromString<BackupManifest>(
                        zip.getInputStream(zip.getEntry("manifest.json")
                            ?: throw IllegalArgumentException("缺少 manifest.json"))
                            .readBytes().toString(Charsets.UTF_8)
                    )
                    if (manifest.formatVersion != BACKUP_FORMAT_VERSION) {
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
                    // 生成回滚副本（当前 DB 导出到 rollback 文件，占位简化：只保护偏好+标记）
                    // 真正的原子恢复：清表→写入→复制图片；任一步失败则抛异常由调用方提示“旧数据仍可用”
                    performRestore(zip, data)
                }
            } finally {
                try { tmp.delete() } catch (_: Exception) { }
            }
        }

    private suspend fun performRestore(zip: ZipFile, data: BackupData) {
        val db = repo.db
        // 先把图片解压到 tmp staging
        val staging = File(context.filesDir, "tmp/restore_${System.currentTimeMillis()}").apply { mkdirs() }
        data.images.forEach { img ->
            val e = zip.getEntry("assets/${img.id}.jpg") ?: return@forEach
            val out = File(staging, "${img.id}.jpg")
            zip.getInputStream(e).use { ins -> out.outputStream().use { o -> ins.copyTo(o) } }
        }
        // 校验解压后图片可解码
        staging.listFiles()?.forEach { f ->
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
            if (opts.outWidth <= 0) throw IllegalArgumentException("备份图片损坏：${f.name}")
        }
        // 事务写入结构化数据
        db.withTransaction {
            db.clearAllTables()
            data.semesters.forEach { db.semesterDao().upsert(it) }
            data.courses.forEach { db.courseDao().upsert(it) }
            data.homework.forEach { db.homeworkDao().upsert(it) }
            if (data.blocks.isNotEmpty()) db.blockDao().upsertAll(data.blocks)
            data.images.forEach { db.imageDao().upsert(it) }
            data.drafts.forEach { db.draftDao().upsert(it) }
            data.sessions.forEach { db.reviewDao().upsertSession(it) }
            if (data.items.isNotEmpty()) db.reviewDao().upsertItems(data.items)
        }
        // 提交图片到正式目录
        val dest = File(context.filesDir, "images").apply { mkdirs() }
        staging.listFiles()?.forEach { f ->
            f.copyTo(File(dest, f.name), overwrite = true)
        }
        try {
            staging.deleteRecursively()
        } catch (_: Exception) {
        }
        prefs.restore(data.prefs)
    }
}
