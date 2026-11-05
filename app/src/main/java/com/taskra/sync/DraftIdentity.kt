package com.taskra.sync

/** 各设备的本地“new”草稿在云端使用不同键；收到其他设备草稿时保留其云端键。 */
internal fun cloudDraftId(localDraftId: String, deviceId: String): String {
    require(Regex("[A-Za-z0-9_-]+").matches(deviceId)) { "设备标识无效" }
    require(localDraftId.isNotBlank()) { "草稿标识无效" }
    return if (localDraftId == "new") "new:$deviceId" else localDraftId
}

internal fun localDraftId(cloudDraftId: String, deviceId: String): String {
    require(Regex("[A-Za-z0-9_-]+").matches(deviceId)) { "设备标识无效" }
    require(cloudDraftId.isNotBlank()) { "草稿标识无效" }
    return if (cloudDraftId == "new:$deviceId") "new" else cloudDraftId
}
