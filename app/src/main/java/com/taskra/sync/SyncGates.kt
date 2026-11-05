package com.taskra.sync

import kotlinx.coroutines.sync.Mutex

/** 同步与备份互斥；编辑器期间不应用远端内容；附件操作始终先持有数据库事务。 */
internal object SyncGates {
    val operation = Mutex()
    val editor = Mutex()
    val assets = Mutex()
    val preferences = Mutex()
}
