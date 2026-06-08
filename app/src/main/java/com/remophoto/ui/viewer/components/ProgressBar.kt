package com.remophoto.ui.viewer.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 全屏底部进度条组件（占位 — Phase 2 完善）
 *
 * 极简设计：细线进度条 + 小圆滑块 + 位置文字 "12 / 5000"。
 * 支持拖动跳转，不支持缩略图预览。
 */
@Composable
fun ViewerProgressBar(
    currentIndex: Int,
    totalCount: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (totalCount <= 0) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Slider(
            value = currentIndex.toFloat(),
            valueRange = 0f..(totalCount - 1).coerceAtLeast(1).toFloat(),
            onValueChange = { onSeek(it.toInt()) },
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.8f),
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )
        Text(
            text = "${currentIndex + 1} / $totalCount",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}
