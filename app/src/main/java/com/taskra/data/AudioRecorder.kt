package com.taskra.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.UUID

data class RecordedAudio(val id: String, val durationMs: Long)

/** 只在前台录音；先落临时文件，停止成功后才提交为作业附件。 */
class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var temporary: File? = null
    private var startedAt = 0L
    val elapsedMs: Long get() = if (recorder == null) 0 else SystemClock.elapsedRealtime() - startedAt

    @Suppress("DEPRECATION")
    fun start() {
        check(recorder == null)
        val file = File(context.filesDir, "tmp/recording-${UUID.randomUUID()}.m4a")
        file.parentFile?.mkdirs()
        val next = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioEncodingBitRate(96000)
            next.setAudioSamplingRate(44100)
            next.setOutputFile(file.absolutePath)
            next.prepare()
            next.start()
            temporary = file
            recorder = next
            startedAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            next.release()
            file.delete()
            throw e
        }
    }

    fun finish(): RecordedAudio {
        val current = checkNotNull(recorder)
        val source = checkNotNull(temporary)
        val duration = elapsedMs
        recorder = null
        temporary = null
        try {
            current.stop()
            check(source.length() > 0) { "录音为空" }
            val id = UUID.randomUUID().toString()
            val destination = audioFile(context, id)
            destination.parentFile?.mkdirs()
            check(source.renameTo(destination)) { "无法保存录音" }
            return RecordedAudio(id, duration)
        } finally {
            current.release()
            source.delete()
        }
    }

    fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        temporary?.delete()
        temporary = null
    }
}

fun audioFile(context: Context, id: String): File {
    require(Regex("[a-zA-Z0-9_-]+").matches(id)) { "录音标识无效" }
    return File(context.filesDir, "audio/$id.m4a")
}

fun audioTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
