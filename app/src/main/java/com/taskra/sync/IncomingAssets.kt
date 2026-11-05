package com.taskra.sync

import android.content.Context
import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import com.taskra.data.AppDatabase
import java.io.File

internal class AssetConflict : Exception()
internal data class PreparedAsset(val asset: SyncAsset, val temporary: File, val destination: File)

/** 入库前校验附件，目标与临时文件位于应用私有存储的同一文件系统。 */
internal class IncomingAssets(private val context: Context, private val db: AppDatabase, private val blobs: PendingBlobStore) {
    fun prepare(assets: List<SyncAsset>): List<PreparedAsset>? {
        require(assets.map { it.kind to it.id }.toSet().size == assets.size) { "附件标识重复" }
        val prepared = mutableListOf<PreparedAsset>()
        try {
            assets.forEach { asset ->
                require(asset.kind in setOf("image", "audio") && Regex("[A-Za-z0-9_-]+").matches(asset.id)) { "附件标识无效" }
                require(Regex("[a-f0-9]{64}").matches(asset.hash)) { "附件校验码无效" }
                val source = try { blobs.get(asset.hash) } catch (_: IllegalArgumentException) {
                    prepared.forEach { it.temporary.delete() }
                    return null
                }
                val destination = when (asset.kind) {
                    "image" -> {
                        val image = requireNotNull(asset.image) { "图片信息缺失" }
                        require(image.id == asset.id && image.relativePath == "images/${asset.id}.jpg") { "图片信息无效" }
                        require(image.sizeBytes == 0L || image.sizeBytes == source.length()) { "图片大小不一致" }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(source.absolutePath, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片文件损坏" }
                        File(context.filesDir, image.relativePath)
                    }
                    else -> {
                        require(asset.image == null && source.length() > 0) { "录音信息无效" }
                        File(context.filesDir, "audio/${asset.id}.m4a")
                    }
                }
                val parent = requireNotNull(destination.parentFile)
                require(parent.isDirectory || parent.mkdirs())
                val temporary = File.createTempFile("incoming-", ".part", parent)
                prepared += PreparedAsset(asset, temporary, destination)
                source.inputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
                require(syncFileSha256(temporary) == asset.hash) { "附件校验失败" }
            }
            return prepared
        } catch (e: Exception) {
            prepared.forEach { it.temporary.delete() }
            throw e
        }
    }

    /** 调用方须先持有 Room 事务及 SyncGates.assets。 */
    suspend fun install(prepared: List<PreparedAsset>) {
        // 在更改任何目标前检查所有同名文件，避免冲突时覆盖现有附件。
        prepared.forEach {
            if (it.destination.exists() && (!it.destination.isFile || syncFileSha256(it.destination) != it.asset.hash)) throw AssetConflict()
        }
        prepared.forEach {
            if (!it.destination.exists()) {
                Os.rename(it.temporary.absolutePath, it.destination.absolutePath)
                val descriptor = Os.open(requireNotNull(it.destination.parentFile).absolutePath, OsConstants.O_RDONLY, 0)
                try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
            }
            it.asset.image?.let { image ->
                val existing = db.imageDao().get(image.id)
                if (existing != null && existing.relativePath != image.relativePath) throw AssetConflict()
                if (existing == null) db.imageDao().upsert(image)
            }
        }
    }
}
