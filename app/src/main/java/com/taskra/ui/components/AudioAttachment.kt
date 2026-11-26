package com.taskra.ui.components

import android.media.MediaPlayer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.taskra.data.audioFile
import com.taskra.data.audioTime
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.TaskraMotion
import com.taskra.ui.motion.applePressScale
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun AudioAttachment(id: String, durationMs: Long, onRemove: (() -> Unit)? = null) {
    val reduced = LocalReduceMotion.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var player by remember(id) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(id) { mutableStateOf(false) }
    var position by remember(id) { mutableStateOf(0L) }
    var error by remember(id) { mutableStateOf(false) }
    val safeDuration = durationMs.coerceAtLeast(1L)

    fun ensurePrepared(): MediaPlayer = player ?: MediaPlayer().also { next ->
        player = next
        next.setDataSource(audioFile(context, id).absolutePath)
        next.prepare()
        next.setOnCompletionListener { playing = false; position = 0; it.seekTo(0) }
    }

    fun seekToFraction(fraction: Float) {
        val target = (fraction.coerceIn(0f, 1f) * safeDuration).toLong()
        runCatching {
            ensurePrepared().seekTo(target.toInt())
            position = target
            error = false
        }.onFailure { error = true }
    }

    DisposableEffect(id, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { player?.pause(); playing = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player?.release(); player = null }
    }
    LaunchedEffect(playing) {
        // 30fps 平滑采样播放进度，配合缓动进度条彻底告别 200ms 跳格感
        while (playing) {
            position = player?.currentPosition?.toLong() ?: 0L
            delay(33)
        }
    }

    val rawProgress = (position.toFloat() / safeDuration).coerceIn(0f, 1f)
    val smoothProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(if (reduced) 0 else 65, easing = LinearEasing),
        label = "audioProgress",
    )
    val wavePhase = if (playing && !reduced) {
        val infinite = rememberInfiniteTransition(label = "audioWave")
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "audioWavePhase",
        ).value
    } else 0f

    val playInteraction = remember { MutableInteractionSource() }
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val activeColor = MaterialTheme.colorScheme.primary
    val baseLabelMedium = MaterialTheme.typography.labelMedium
    val timeStyle = remember(baseLabelMedium) {
        baseLabelMedium.copy(fontFeatureSettings = "tnum")
    }

    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    try {
                        if (playing) { player?.pause(); playing = false }
                        else {
                            val p = ensurePrepared()
                            p.start(); playing = true; error = false
                        }
                    } catch (_: Exception) { player?.release(); player = null; playing = false; error = true }
                },
                interactionSource = playInteraction,
                modifier = Modifier.applePressScale(playInteraction, 0.88f),
            ) {
                AnimatedContent(
                    targetState = playing,
                    transitionSpec = { TaskraMotion.iconSwapSpec(reduced) },
                    label = "audioPlayPause",
                ) { isPlaying ->
                    Icon(
                        imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (isPlaying) "暂停录音" else "播放录音",
                        tint = activeColor,
                    )
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playing && !reduced) {
                        // 播放中三竖条轻柔律动声波（纯 Canvas 绘制，零重组）
                        Canvas(Modifier.size(20.dp)) {
                            val barW = 2.2.dp.toPx()
                            val gap = 3.2.dp.toPx()
                            val startX = (size.width - (barW * 3 + gap * 2)) / 2f
                            for (i in 0..2) {
                                val amp = 0.35f + 0.55f * ((sin(wavePhase + i * 1.4f) + 1f) * 0.5f)
                                val h = size.height * 0.75f * amp
                                val top = (size.height - h) / 2f
                                drawRoundRect(
                                    color = activeColor,
                                    topLeft = Offset(startX + i * (barW + gap), top),
                                    size = Size(barW, h),
                                    cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                                )
                            }
                        }
                    } else {
                        Icon(Icons.Outlined.GraphicEq, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    AnimatedContent(
                        targetState = error,
                        transitionSpec = { TaskraMotion.crossfadeSpec(reduced) },
                        label = "audioTitle",
                    ) { hasError ->
                        Text(
                            if (hasError) "录音无法播放" else "录音",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (playing || position > 0) "${audioTime(position)} / ${audioTime(durationMs)}"
                        else audioTime(durationMs),
                        style = timeStyle,
                    )
                }
                // 支持直接点击或拖动跟手跳转播放进度
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .pointerInput(id, safeDuration) {
                            detectTapGestures { offset ->
                                seekToFraction(offset.x / size.width.coerceAtLeast(1))
                            }
                        }
                        .pointerInput(id, safeDuration) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                seekToFraction(change.position.x / size.width.coerceAtLeast(1))
                            }
                        },
                ) {
                    val cy = size.height / 2f
                    val trackH = 3.dp.toPx()
                    drawLine(trackColor, Offset(0f, cy), Offset(size.width, cy), trackH, StrokeCap.Round)
                    val endX = size.width * smoothProgress
                    if (endX > 0.5f) {
                        drawLine(activeColor, Offset(0f, cy), Offset(endX, cy), trackH, StrokeCap.Round)
                    }
                    if (playing || position > 0) {
                        drawCircle(activeColor, radius = 4.5.dp.toPx(), center = Offset(endX, cy))
                    }
                }
            }
            if (onRemove != null) {
                val removeInteraction = remember { MutableInteractionSource() }
                IconButton(
                    onClick = onRemove,
                    interactionSource = removeInteraction,
                    modifier = Modifier.applePressScale(removeInteraction, 0.88f),
                ) { Icon(Icons.Outlined.Close, "删除录音") }
            }
        }
    }
}
