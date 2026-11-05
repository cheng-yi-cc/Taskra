package com.taskra.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.taskra.ui.components.PaperTopBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.taskra.TaskraApp
import com.taskra.data.BlockKind
import com.taskra.data.BlockRegion
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(app: TaskraApp, homeworkId: String, startImageId: String, onBack: () -> Unit) {
    var images by remember { mutableStateOf(listOf(startImageId)) }
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(homeworkId, startImageId) {
        try {
            val all = app.repo.db.blockDao().listByHomework(homeworkId)
            val stored = (all.filter { it.region == BlockRegion.QUESTION } +
                all.filter { it.region == BlockRegion.SOLUTION })
                .filter { it.kind == BlockKind.IMAGE }
                .mapNotNull { it.imageId }
            // 未落库的新图不在列表里：退化为只看这一张。
            images = if (stored.contains(startImageId)) stored else listOf(startImageId)
            index = images.indexOf(startImageId).coerceAtLeast(0)
        } catch (_: Exception) {
            images = listOf(startImageId)
            index = 0
        }
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (images.isEmpty()) "图片" else "图片 ${index + 1}/${images.size}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(pad)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale <= 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val id = images.getOrNull(index)
            if (id == null) {
                Text("无图片")
            } else {
                AsyncImage(
                    model = File(app.filesDir, "images/$id.jpg"),
                    contentDescription = "原图",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                    contentScale = ContentScale.Fit,
                )
            }
            if (index > 0) {
                IconButton(
                    onClick = { index--; scale = 1f; offset = Offset.Zero },
                    modifier = Modifier.align(Alignment.CenterStart),
                ) { Icon(Icons.Outlined.ArrowBack, contentDescription = "上一张") }
            }
            if (index < images.size - 1) {
                IconButton(
                    onClick = { index++; scale = 1f; offset = Offset.Zero },
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) { Icon(Icons.Outlined.ArrowForward, contentDescription = "下一张") }
            }
        }
    }
}
