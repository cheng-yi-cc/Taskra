package com.taskra.sync

import android.content.Context
import android.provider.Settings

internal class ClockSampleStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("sync_clock", Context.MODE_PRIVATE)

    fun bootId(): Long = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()

    fun load(): ClockSample? {
        if (!prefs.contains("server")) return null
        return ClockSample(
            prefs.getLong("wall_before", -1), prefs.getLong("wall_after", -1),
            prefs.getLong("elapsed_before", -1), prefs.getLong("elapsed_after", -1),
            prefs.getLong("server", -1), prefs.getLong("boot", -1),
        )
    }

    fun save(sample: ClockSample) {
        check(prefs.edit()
            .putLong("wall_before", sample.wallBefore).putLong("wall_after", sample.wallAfter)
            .putLong("elapsed_before", sample.elapsedBefore).putLong("elapsed_after", sample.elapsedAfter)
            .putLong("server", sample.serverTime).putLong("boot", sample.bootId).commit()) { "无法保存同步时间" }
    }
}
