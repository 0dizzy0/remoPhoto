package com.remophoto.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.remophoto.ui.theme.ViewerBackground

/**
 * 全屏浏览页面（占位 — Phase 1 实现）
 *
 * 全屏显示单张图片，支持双指缩放、手势切换、自动播放等。
 */
@Composable
fun FullScreenViewer(
    albumId: Long,
    imageIndex: Int,
    onBack: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ViewerBackground),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "全屏浏览\n(Phase 1 实现)\n\nalbumId: $albumId\nindex: $imageIndex",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}
