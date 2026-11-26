package com.taskra.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.taskra.TaskraApp
import com.taskra.data.BlockKind
import com.taskra.data.BlockRegion
import com.taskra.ui.components.PaperTopBar as TopAppBar
import com.taskra.ui.motion.LocalReduceMotion
import com.taskra.ui.motion.RollingCountText
import com.taskra.ui.motion.TaskraMotion
import com.taskra.ui.motion.applePressScale
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(app: TaskraApp, homeworkId: String, startImageId: String, onBack: () -> Unit) {
    val reduced = LocalReduceMotion.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf(listOf(startImageId)) }
    var startPage by remember { mutableStateOf(0) }
    var chromeVisible by remember { mutableStateOf(true) }
    var pageZoomed by remember { mutableStateOf(false) }
    val dismissOffsetY = remember { Animatable(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { 116.dp.toPx() }

    LaunchedEffect(homeworkId, startImageId) {
        try {
            val all = app.repo.db.blockDao().listByHomework(homeworkId)
            val stored = (all.filter { it.region == BlockRegion.QUESTION } +
                all.filter { it.region == BlockRegion.SOLUTION })
                .filter { it.kind == BlockKind.IMAGE }
                .mapNotNull { it.imageId }
            // 未落库的新图不在列表里：退化为只看这一张。
            images = if (stored.contains(startImageId)) stored else listOf(startImageId)
            startPage = images.indexOf(startImageId).coerceAtLeast(0)
        } catch (_: Exception) {
            images = listOf(startImageId)
            startPage = 0
        }
    }

    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { images.size.coerceAtLeast(1) })
    LaunchedEffect(images, startPage) {
        if (images.isNotEmpty() && startPage in images.indices && pagerState.currentPage != startPage) {
            pagerState.scrollToPage(startPage)
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        pageZoomed = false
    }

    val bgAlpha = (1f - (abs(dismissOffsetY.value) / (dismissThresholdPx * 2.8f)).coerceIn(0f, 0.72f))
    val dismissScale = if (reduced) 1f else (1f - (abs(dismissOffsetY.value) / (dismissThresholdPx * 7.5f)).coerceIn(0f, 0.14f))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = bgAlpha)),
    ) {
        if (images.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无图片")
            }
        } else {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !pageZoomed && abs(dismissOffsetY.value) < 4f,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = dismissOffsetY.value
                        scaleX = dismissScale
                        scaleY = dismissScale
                    },
            ) { page ->
                val imgId = images[page]
                val scale = remember(imgId) { Animatable(1f) }
                val offset = remember(imgId) { Animatable(Offset.Zero, Offset.VectorConverter) }
                var containerSize by remember { mutableStateOf(IntSize.Zero) }

                fun clampOffset(raw: Offset, s: Float): Offset {
                    if (s <= 1f || containerSize == IntSize.Zero) return Offset.Zero
                    val maxX = (containerSize.width * (s - 1f)) / 2f
                    val maxY = (containerSize.height * (s - 1f)) / 2f
                    return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
                }

                // 翻到其他页时复位当前页缩放，避免切回时状态突兀
                LaunchedEffect(pagerState.currentPage) {
                    if (pagerState.currentPage != page && scale.value != 1f) {
                        scale.snapTo(1f)
                        offset.snapTo(Offset.Zero)
                    }
                }

                val request = remember(imgId, reduced) {
                    ImageRequest.Builder(context)
                        .data(File(app.filesDir, "images/$imgId.jpg"))
                        .crossfade(if (reduced) 0 else TaskraMotion.QuickMs)
                        .build()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { containerSize = it }
                        .pointerInput(imgId, reduced) {
                            detectTapGestures(
                                onTap = { chromeVisible = !chromeVisible },
                                onDoubleTap = { tapPos ->
                                    scope.launch {
                                        if (scale.value > 1.08f) {
                                            pageZoomed = false
                                            val spec = if (reduced) tween<Float>(TaskraMotion.ReducedFadeMs) else TaskraMotion.spatialSpring()
                                            val offSpec = if (reduced) tween<Offset>(TaskraMotion.ReducedFadeMs) else TaskraMotion.spatialSpring()
                                            launch { scale.animateTo(1f, spec) }
                                            launch { offset.animateTo(Offset.Zero, offSpec) }
                                        } else {
                                            val targetScale = 2.5f
                                            val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                            val targetOffset = clampOffset((center - tapPos) * (targetScale - 1f), targetScale)
                                            pageZoomed = true
                                            val spec = if (reduced) tween<Float>(TaskraMotion.ReducedFadeMs) else TaskraMotion.spatialSpring()
                                            val offSpec = if (reduced) tween<Offset>(TaskraMotion.ReducedFadeMs) else TaskraMotion.spatialSpring()
                                            launch { scale.animateTo(targetScale, spec) }
                                            launch { offset.animateTo(targetOffset, offSpec) }
                                        }
                                    }
                                },
                            )
                        }
                        .pointerInput(imgId, reduced) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var verticalDismissing = false
                                var zoomInteracted = false
                                var panAccum = Offset.Zero
                                val slop = viewConfiguration.touchSlop
                                do {
                                    val event = awaitPointerEvent()
                                    val pressedCount = event.changes.count { it.pressed }
                                    if (pressedCount >= 2) {
                                        zoomInteracted = true
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        val nextScale = (scale.value * zoom).coerceIn(0.82f, 5.2f)
                                        val nextOffset = offset.value + pan
                                        scope.launch {
                                            scale.snapTo(nextScale)
                                            offset.snapTo(nextOffset)
                                        }
                                        pageZoomed = nextScale > 1.02f
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } else if (pressedCount == 1) {
                                        val pan = event.calculatePan()
                                        if (scale.value > 1.02f) {
                                            zoomInteracted = true
                                            val nextOffset = offset.value + pan
                                            scope.launch { offset.snapTo(nextOffset) }
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        } else if (!zoomInteracted) {
                                            panAccum += pan
                                            if (!verticalDismissing && abs(panAccum.y) > slop && abs(panAccum.y) > abs(panAccum.x) * 1.25f) {
                                                verticalDismissing = true
                                            }
                                            if (verticalDismissing) {
                                                scope.launch { dismissOffsetY.snapTo(dismissOffsetY.value + pan.y) }
                                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                            }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                if (verticalDismissing) {
                                    if (abs(dismissOffsetY.value) > dismissThresholdPx) {
                                        onBack()
                                    } else {
                                        scope.launch {
                                            dismissOffsetY.animateTo(
                                                0f,
                                                if (reduced) tween(TaskraMotion.ReducedFadeMs) else TaskraMotion.spatialSpring(),
                                            )
                                        }
                                    }
                                } else if (zoomInteracted) {
                                    val finalScale = scale.value.coerceIn(1f, 4.5f)
                                    val finalOffset = clampOffset(offset.value, finalScale)
                                    pageZoomed = finalScale > 1.02f
                                    scope.launch {
                                        val sSpec = if (reduced) tween<Float>(TaskraMotion.ReducedFadeMs) else TaskraMotion.spatialSpring()
                                        val oSpec = if (reduced) tween<Offset>(TaskraMotion.ReducedFadeMs) else TaskraMotion.spatialSpring()
                                        if (scale.value != finalScale) launch { scale.animateTo(finalScale, sSpec) }
                                        if (offset.value != finalOffset) launch { offset.animateTo(finalOffset, oSpec) }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = request,
                        contentDescription = "原图",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale.value
                                scaleY = scale.value
                                translationX = offset.value.x
                                translationY = offset.value.y
                            },
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        // 悬浮顶栏：单击图片可在沉浸看图与控制栏间优雅淡入淡出
        AnimatedVisibility(
            visible = chromeVisible && abs(dismissOffsetY.value) < 16f,
            enter = if (reduced) fadeIn(tween(TaskraMotion.ReducedFadeMs))
            else fadeIn(tween(TaskraMotion.QuickMs, easing = TaskraMotion.Decelerate)) +
                slideInVertically(tween(TaskraMotion.StandardMs, easing = TaskraMotion.FluidOut)) { -it / 3 },
            exit = if (reduced) fadeOut(tween(TaskraMotion.ReducedFadeMs))
            else fadeOut(tween(130, easing = TaskraMotion.Standard)) +
                slideOutVertically(tween(180, easing = TaskraMotion.Standard)) { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            TopAppBar(
                title = {
                    if (images.isEmpty()) Text("图片")
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("图片 ")
                        RollingCountText(pagerState.currentPage + 1)
                        Text("/${images.size}")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }

        val currentIndex = pagerState.currentPage
        AnimatedVisibility(
            visible = chromeVisible && currentIndex > 0 && abs(dismissOffsetY.value) < 16f,
            enter = TaskraMotion.popEnter(reduced),
            exit = TaskraMotion.popExit(reduced),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
        ) {
            val prevInteraction = remember { MutableInteractionSource() }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                modifier = Modifier.applePressScale(prevInteraction, 0.88f),
            ) {
                IconButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage((currentIndex - 1).coerceAtLeast(0)) } },
                    interactionSource = prevInteraction,
                ) { Icon(Icons.Outlined.ArrowBack, contentDescription = "上一张") }
            }
        }
        AnimatedVisibility(
            visible = chromeVisible && currentIndex < images.size - 1 && abs(dismissOffsetY.value) < 16f,
            enter = TaskraMotion.popEnter(reduced),
            exit = TaskraMotion.popExit(reduced),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
        ) {
            val nextInteraction = remember { MutableInteractionSource() }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                modifier = Modifier.applePressScale(nextInteraction, 0.88f),
            ) {
                IconButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage((currentIndex + 1).coerceAtMost(images.lastIndex)) } },
                    interactionSource = nextInteraction,
                ) { Icon(Icons.Outlined.ArrowForward, contentDescription = "下一张") }
            }
        }
    }
}
