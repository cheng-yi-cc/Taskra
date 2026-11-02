package com.taskra.ui.components

import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.taskra.data.audioFile
import com.taskra.data.audioTime
import kotlinx.coroutines.delay

@Composable
fun AudioAttachment(id: String, durationMs: Long, onRemove: (() -> Unit)? = null) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var player by remember(id) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(id) { mutableStateOf(false) }
    var position by remember(id) { mutableStateOf(0L) }
    var error by remember(id) { mutableStateOf(false) }
    DisposableEffect(id, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { player?.pause(); playing = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player?.release(); player = null }
    }
    LaunchedEffect(playing) {
        while (playing) { position = player?.currentPosition?.toLong() ?: 0; delay(200) }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                try {
                    if (playing) { player?.pause(); playing = false }
                    else {
                        val p = player ?: MediaPlayer().also { next ->
                            player = next
                            next.setDataSource(audioFile(context, id).absolutePath)
                            next.prepare()
                            next.setOnCompletionListener { playing = false; position = 0; it.seekTo(0) }
                        }
                        p.start(); playing = true; error = false
                    }
                } catch (_: Exception) { player?.release(); player = null; playing = false; error = true }
            }) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "暂停录音" else "播放录音", tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.GraphicEq, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(if (error) "录音无法播放" else "录音", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text(audioTime(if (playing || position > 0) position else durationMs), style = MaterialTheme.typography.labelMedium)
                }
                LinearProgressIndicator(progress = { (position.toFloat() / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, "删除录音") }
        }
    }
}
