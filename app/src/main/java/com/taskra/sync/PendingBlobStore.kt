package com.taskra.sync

import java.io.File
import java.security.MessageDigest

/** 已冻结发送版本引用的附件放在持久目录，不依赖可能被编辑或删除的原文件。 */
internal class PendingBlobStore(private val directory: File) {
    init { require(directory.isDirectory || directory.mkdirs()) { "无法创建同步暂存目录" } }

    fun stage(source: File): Pair<String, File> {
        require(source.isFile && source.length() in 1..500L * 1024 * 1024) { "附件不存在、为空或过大" }
        val sizeBefore = source.length()
        val modifiedBefore = source.lastModified()
        val temporary = File.createTempFile("pending-", ".part", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            source.inputStream().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 500L * 1024 * 1024) { "附件过大" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            require(total == sizeBefore && source.isFile && source.length() == sizeBefore &&
                source.lastModified() == modifiedBefore && syncFileSha256(source) == hash) { "附件在准备同步时发生变化" }
            val destination = File(directory, hash)
            AtomicSyncFile.publish(temporary, destination, hash)
            return hash to destination
        } finally {
            temporary.delete()
        }
    }

    fun get(hash: String): File {
        require(Regex("[a-f0-9]{64}").matches(hash)) { "附件标识无效" }
        val file = File(directory, hash)
        require(file.isFile && syncFileSha256(file) == hash) { "同步暂存附件缺失或损坏" }
        return file
    }
}
