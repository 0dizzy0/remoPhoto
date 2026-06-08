package com.remophoto.ui.viewer.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 自动播放（幻灯片）控制组件（占位 — Phase 2 完善）
 *
 * 播放/暂停按钮 + 间隔选择器。
 */
@Composable
fun SlideshowControl(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    intervalSeconds: Int,
    onIntervalChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 播放/暂停按钮
        IconButton(onClick = onTogglePlay) {
            Text(
                text = if (isPlaying) "⏸" else "▶️",
                style = MaterialTheme.typography.titleLarge
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // 间隔选择（Phase 2 替换为下拉菜单）
        Text(
            text = "${intervalSeconds}s",
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
