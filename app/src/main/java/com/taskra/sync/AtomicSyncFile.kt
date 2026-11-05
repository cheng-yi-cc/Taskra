package com.taskra.sync

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.security.MessageDigest

/** 仅用于由本进程独占管理的应用私有、内容寻址目录。 */
internal object AtomicSyncFile {
    private val lock = Any()

    fun publish(staged: File, destination: File, digest: String) = synchronized(lock) {
        require(Regex("[a-f0-9]{64}").matches(digest) && destination.name == digest)
        require(staged.parentFile?.canonicalFile == destination.parentFile?.canonicalFile)
        require(staged.isFile && syncFileSha256(staged) == digest) { "附件校验失败" }
        if (destination.exists()) {
            require(destination.isFile && syncFileSha256(destination) == digest) { "已有附件校验失败" }
        } else {
            // 同目录 rename 是原子操作；应用只有一个进程，并以同一把锁串行发布。
            Os.rename(staged.absolutePath, destination.absolutePath)
            val descriptor = Os.open(requireNotNull(destination.parentFile).absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        }
    }
}

internal fun syncFileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
