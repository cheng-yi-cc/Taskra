package com.taskra.sync

// 同步表由迁移显式管理，不纳入 Room 的 clearAllTables，避免备份恢复误删待同步记录。
internal data class SyncControlEntity(
    val id: Int,
    val nextSequence: Long,
    val applyingRemote: Int,
    val bootstrapComplete: Int,
    val enabled: Int,
    val pullCursor: Long,
    val binding: String,
)

internal data class SyncDirtyEntity(
    val kind: String,
    val recordId: String,
    val modifiedAt: Long,
    val sequence: Long,
    val deleted: Int,
)

internal data class SyncOutboxEntity(
    val kind: String,
    val recordId: String,
    val sequence: Long,
    val payload: String,
)
