package com.taskra.sync

/**
 * 同步记录的版本。删除也必须有版本（value 为 null），以避免旧设备重新上传已删数据。
 * 同一份作业及其内容块应作为一个整体，不能逐块使用本函数合并。
 * 此合并器不校验设备时钟；调用方必须先处理时钟异常，否则不能自动覆盖数据。
 */
data class SyncVersion(val modifiedAt: Long, val deviceId: String, val sequence: Long) : Comparable<SyncVersion> {
    override fun compareTo(other: SyncVersion): Int =
        compareValuesBy(this, other, SyncVersion::modifiedAt, SyncVersion::deviceId, SyncVersion::sequence)
}

data class VersionedRecord<T>(val version: SyncVersion, val value: T?)

/**
 * 合并独立逻辑记录。两端有同一版本但内容不一致意味着数据损坏或版本复用；
 * 此时必须停止同步，绝不能静默选取一方。
 */
internal fun <T> mergeRecords(
    local: Map<String, VersionedRecord<T>>,
    remote: Map<String, VersionedRecord<T>>,
): Map<String, VersionedRecord<T>> {
    val result = local.toMutableMap()
    remote.forEach { (key, incoming) ->
        val current = result[key]
        when {
            current == null || incoming.version > current.version -> result[key] = incoming
            incoming.version == current.version -> require(incoming.value == current.value) {
                "同步记录版本相同但内容不同：$key"
            }
        }
    }
    return result
}
