package com.taskra.sync

import kotlinx.coroutines.CancellationException

internal const val DEFAULT_SYNC_ENDPOINT = "https://sync.chengyi.me/taskra-sync"
internal const val LEGACY_SYNC_ENDPOINT = "https://chengyi-thinkpad-t420.taild0ff49.ts.net/taskra-sync"

/** 仅迁移旧默认地址；先用原设备凭据验证新地址，自定义地址保持原样。 */
internal suspend fun upgradedSyncEndpoint(
    current: SyncCredentials,
    verify: suspend (SyncCredentials) -> Unit,
): SyncCredentials {
    if (current.endpoint.trimEnd('/') != LEGACY_SYNC_ENDPOINT) return current
    val candidate = current.copy(endpoint = DEFAULT_SYNC_ENDPOINT)
    return try {
        verify(candidate)
        candidate
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        current
    }
}
