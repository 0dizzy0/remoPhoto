package com.remophoto.ui.viewer.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 自动播放（幻灯片）控制组件
 *
 * 播放/暂停按钮 + 间隔选择下拉菜单。
 * 紧凑布局，半透明背景。
 */
@Composable
fun SlideshowControl(
    isPlaying: Boolean,
    intervalSeconds: Int,
    onTogglePlay: () -> Unit,
    onIntervalChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showIntervalMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 播放/暂停按钮
        TextButton(
            onClick = onTogglePlay,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
        ) {
            Text(
                text = if (isPlaying) "⏸ 暂停" else "▶ 播放",
                color = Color.White,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 间隔选择按钮
        Box {
            TextButton(
                onClick = { showIntervalMenu = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    text = "${intervalSeconds}s",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }

            DropdownMenu(
                expanded = showIntervalMenu,
                onDismissRequest = { showIntervalMenu = false },
                offset = DpOffset(0.dp, 0.dp)
            ) {
                listOf(1, 3, 5, 10, 30, 60).forEach { sec ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (sec == intervalSeconds) "● ${sec}s" else "${sec}s",
                                color = if (sec == intervalSeconds)
                                    MaterialTheme.colorScheme.primary
                                else
                                    Color.Unspecified
                            )
                        },
                        onClick = {
                            onIntervalChange(sec)
                            showIntervalMenu = false
                        }
                    )
                }
            }
        }
    }
}
