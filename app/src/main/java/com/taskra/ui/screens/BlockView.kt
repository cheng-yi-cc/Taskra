package com.taskra.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.taskra.TaskraApp
import com.taskra.data.BlockKind
import com.taskra.data.ContentBlock
import com.taskra.util.buildAnnotated
import com.taskra.util.decodeSpans
import com.taskra.util.rememberHighlightColor
import java.io.File

/** 只读内容块渲染（复习页等复用）。编辑器里另有一套可输入版本。 */
@Composable
fun BlockView(app: TaskraApp, b: ContentBlock, onClickImage: () -> Unit) {
    if (b.kind == BlockKind.TEXT) {
        if (!b.text.isNullOrBlank()) {
            val highlight = rememberHighlightColor()
            val baseSize = MaterialTheme.typography.bodyLarge.fontSize
            val annotated = remember(b.text, b.spanJson, highlight, baseSize) {
                buildAnnotated(b.text, decodeSpans(b.spanJson), highlight, baseSize)
            }
            Text(annotated, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
    } else if (b.kind == BlockKind.AUDIO && b.audioId != null) {
        com.taskra.ui.components.AudioAttachment(b.audioId, b.audioDurationMs)
    } else {
        val assetId = b.imageId
        if (assetId != null) {
            // 按需加载：只加载当前项，不一次解码全部原图（Coil 按需加载）
            val f = remember(assetId) {
                // 同步查相对路径需 IO；此处用约定路径 images/<id>.jpg
                File(app.filesDir, "images/$assetId.jpg")
            }
            AsyncImage(
                model = f,
                contentDescription = "作业图片",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onClickImage),
                contentScale = ContentScale.FillWidth, // 保持比例，不裁掉题号公式
            )
        }
    }
}