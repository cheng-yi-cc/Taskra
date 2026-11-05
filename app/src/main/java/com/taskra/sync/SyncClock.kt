package com.taskra.sync

import kotlin.math.abs

/** 一次服务器对时，壁钟和单调时钟在同一设备启动周期内采样。 */
internal data class ClockSample(
    val wallBefore: Long,
    val wallAfter: Long,
    val elapsedBefore: Long,
    val elapsedAfter: Long,
    val serverTime: Long,
    val bootId: Long,
)

internal data class AssessedTime(val modifiedAt: Long, val reliable: Boolean)

/** 时钟无法确认时，交给服务器按接收顺序处理；调用方必须把结果持久化后再发送。 */
internal fun assessChangeTime(
    changedAt: Long,
    sample: ClockSample?,
    nowWall: Long,
    nowElapsed: Long,
    bootId: Long,
): AssessedTime {
    fun fallback() = AssessedTime(changedAt, false)
    val s = sample ?: return fallback()
    val maxTimestamp = 4_102_444_800_000L // 2100-01-01 UTC
    if (listOf(changedAt, nowWall, nowElapsed, s.wallBefore, s.wallAfter, s.elapsedBefore, s.elapsedAfter, s.serverTime)
            .any { it !in 0..maxTimestamp } || bootId < 0 || s.bootId < 0) return fallback()
    val rtt = s.elapsedAfter - s.elapsedBefore
    val wallRtt = s.wallAfter - s.wallBefore
    if (bootId != s.bootId || rtt !in 0..3_000 || abs(wallRtt - rtt) > 1_000) return fallback()
    val sampleWall = s.wallBefore + wallRtt / 2
    val sampleElapsed = s.elapsedBefore + rtt / 2
    val age = nowElapsed - sampleElapsed
    if (age !in 0..86_400_000 || abs((nowWall - sampleWall) - age) > 5_000) return fallback()
    // 对时之前的离线修改无法验证当时的时钟；时间差过大时也按服务器顺序。
    if (changedAt < s.wallAfter || changedAt > nowWall + 5_000) return fallback()
    val offset = s.serverTime - sampleWall
    if (abs(offset) > 60_000) return fallback()
    val corrected = try { Math.addExact(changedAt, offset) } catch (_: ArithmeticException) { return fallback() }
    if (corrected < 0) return fallback()
    return AssessedTime(corrected, true)
}
