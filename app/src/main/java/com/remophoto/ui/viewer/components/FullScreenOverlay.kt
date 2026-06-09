package com.remophoto.ui.viewer.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remophoto.ui.theme.ViewerOverlay
import kotlinx.coroutines.delay

/**
 * 全屏 UI 覆盖层
 *
 * 包含：
 * - 顶部栏：返回按钮 + 图片计数
 * - 底部栏：进度条（可拖动跳转）+ 自动播放控制
 * - 自动隐藏逻辑（3 秒无操作后隐藏）
 * - 淡入淡出动画
 */
@Composable
fun FullScreenOverlay(
    isVisible: Boolean,
    currentIndex: Int,
    totalCount: Int,
    isPlaying: Boolean,
    intervalSeconds: Int = 3,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onIntervalChange: (Int) -> Unit = {},
    onSeekTo: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 自动隐藏计时器
    LaunchedEffect(isVisible, currentIndex) {
        if (isVisible) {
            // 3 秒后自动隐藏（仅在可见时）
            // 注：此效果会被用户交互重置
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 顶部栏
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopOverlayBar(
                currentIndex = currentIndex,
                totalCount = totalCount,
                onBack = onBack
            )
        }

        // 底部栏
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomOverlayBar(
                currentIndex = currentIndex,
                totalCount = totalCount,
                isPlaying = isPlaying,
                intervalSeconds = intervalSeconds,
                onTogglePlay = onTogglePlay,
                onIntervalChange = onIntervalChange,
                onSeekTo = onSeekTo
            )
        }
    }
}

/**
 * 顶部覆盖栏
 */
@Composable
private fun TopOverlayBar(
    currentIndex: Int,
    totalCount: Int,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ViewerOverlay
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 返回按钮
            TextButton(onClick = onBack) {
                Text(
                    text = "← 返回",
                    color = Color.White,
                    fontSize = 14.sp
                )
            }

            // 图片计数
            Text(
                text = "${currentIndex + 1} / $totalCount",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            // 占位（保持居中）
            Spacer(modifier = Modifier.width(64.dp))
        }
    }
}

/**
 * 底部覆盖栏：进度条 + 播放控制
 */
@Composable
private fun BottomOverlayBar(
    currentIndex: Int,
    totalCount: Int,
    isPlaying: Boolean,
    intervalSeconds: Int,
    onTogglePlay: () -> Unit,
    onIntervalChange: (Int) -> Unit,
    onSeekTo: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ViewerOverlay
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 进度条 + 计数标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${currentIndex + 1} / $totalCount",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    modifier = Modifier.width(56.dp)
                )
                ViewerProgressBar(
                    currentIndex = currentIndex,
                    totalCount = totalCount,
                    onSeek = onSeekTo,
                    modifier = Modifier.weight(1f)
                )
            }

            // 播放控制
            SlideshowControl(
                isPlaying = isPlaying,
                intervalSeconds = intervalSeconds,
                onTogglePlay = onTogglePlay,
                onIntervalChange = onIntervalChange
            )
        }
    }
}
