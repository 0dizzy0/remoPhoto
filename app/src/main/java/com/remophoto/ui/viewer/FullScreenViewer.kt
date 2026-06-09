package com.remophoto.ui.viewer

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remophoto.domain.model.ImageItem
import com.remophoto.ui.theme.ViewerBackground
import com.remophoto.ui.viewer.components.FullScreenImage
import com.remophoto.ui.viewer.components.FullScreenOverlay
import com.remophoto.ui.viewer.components.ZoomableImage
import com.remophoto.util.AppLogger
import com.remophoto.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * 全屏浏览页面
 *
 * 功能：
 * - HorizontalPager 左右滑动切换图片
 * - 双指缩放 1×~5×（ZoomableImage）
 * - 双击 FIT↔CROP 切换
 * - 单击显示/隐藏 UI
 * - 自动播放（幻灯片模式）
 * - 进度条拖动跳转
 * - GIF/WebP 动图自动播放
 * - 横竖屏自适应
 * - 隐藏状态栏/导航栏
 */
@Composable
fun FullScreenViewer(
    albumId: Long,
    imageIndex: Int,
    onBack: () -> Unit = {}
) {
    val viewModel: FullScreenViewModel = viewModel()

    val images by viewModel.images.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val scale by viewModel.scale.collectAsState()
    val isUiVisible by viewModel.isUiVisible.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

    // 加载图片
    LaunchedEffect(albumId) {
        AppLogger.i(TAG, "开始加载相册图片: albumId=$albumId, targetIndex=$imageIndex")
        viewModel.loadImages(albumId, imageIndex)
    }

    val pagerState = rememberPagerState(
        initialPage = imageIndex,
        pageCount = { images.size.coerceAtLeast(1) }
    )

    // ===== 初始化状态机：加载中 → 定位目标页 → 启用同步 =====
    // 用两个标志位防止 pager 异步状态过渡期间触发错误的同步

    /** 图片首次加载完成 */
    var imagesReady by remember { mutableStateOf(false) }
    /** 初始导航已完成（pager 确认在目标页） */
    var initialSyncDone by remember { mutableStateOf(false) }

    LaunchedEffect(albumId) {
        imagesReady = false
        initialSyncDone = false
    }

    // 阶段 1：等待图片加载 → 跳转到目标页
    LaunchedEffect(images.size) {
        if (!imagesReady && images.isNotEmpty()) {
            imagesReady = true
            AppLogger.i(TAG,
                "图片首次就绪: count=${images.size}, targetIndex=$imageIndex"
            )
            if (imageIndex in images.indices) {
                viewModel.goToImage(imageIndex)
                pagerState.scrollToPage(imageIndex)
                AppLogger.d(TAG, "初始跳转请求: index=$imageIndex")
                // 等待 pager 实际到达目标页（超时 2 秒保护）
                try {
                    withTimeout(2000L) {
                        snapshotFlow { pagerState.currentPage }
                            .first { it == imageIndex }
                    }
                    AppLogger.i(TAG, "初始同步完成: pager 已到达 page=$imageIndex")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "初始同步超时或取消: ${e.message}, 当前 page=${pagerState.currentPage}")
                }
                initialSyncDone = true
            } else {
                initialSyncDone = true
            }
        }
    }

    // 同步 pager → ViewModel（用户滑动 pager 时）
    // 仅在初始同步完成后启用，避免初始化期间的错误同步
    LaunchedEffect(pagerState.currentPage) {
        if (initialSyncDone && pagerState.currentPage != viewModel.currentIndex.value) {
            AppLogger.d(TAG,
                "pager→VM: currentPage=${pagerState.currentPage}, " +
                "vmIndex=${viewModel.currentIndex.value}"
            )
            viewModel.goToImage(pagerState.currentPage)
        }
    }

    // 同步 ViewModel → pager（进度条 seek / 自动播放时）
    LaunchedEffect(currentIndex) {
        if (initialSyncDone && currentIndex != pagerState.currentPage) {
            AppLogger.d(TAG,
                "VM→pager: currentIndex=$currentIndex, " +
                "pagerPage=${pagerState.currentPage}"
            )
            pagerState.scrollToPage(currentIndex)
        }
    }

    // 自动播放定时器
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                delay(viewModel.playIntervalMs.value)
                viewModel.nextImage()
            }
        }
    }

    // UI 自动隐藏计时器
    LaunchedEffect(isUiVisible, currentIndex) {
        if (isUiVisible) {
            delay(Constants.UI_AUTO_HIDE_DELAY_MS)
            viewModel.hideUi()
        }
    }

    // 全屏沉浸：使用系统 UI Flag 方式隐藏状态栏（兼容所有 ROM）
    // LAYOUT_FULLSCREEN + LAYOUT_HIDE_NAVIGATION = 内容延伸到栏后方
    // FULLSCREEN + HIDE_NAVIGATION + IMMERSIVE_STICKY = 隐藏栏 + 边缘滑动暂显
    val context = androidx.compose.ui.platform.LocalContext.current
    @Suppress("DEPRECATION")
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window ?: return@DisposableEffect onDispose { }
        val decorView = window.decorView
        val originalUiVisibility = decorView.systemUiVisibility

        AppLogger.d(TAG, "全屏前 UI visibility: $originalUiVisibility")

        decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        AppLogger.d(TAG, "全屏 UI flag 已设置: LAYOUT_FULLSCREEN + FULLSCREEN + IMMERSIVE_STICKY")

        onDispose {
            decorView.systemUiVisibility = originalUiVisibility
            AppLogger.d(TAG, "全屏 UI flag 已恢复: $originalUiVisibility")
        }
    }

    // 从小到大的扩张动画
    var animVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        animVisible = true
        AppLogger.d(TAG, "缩放动画已触发")
    }

    AnimatedVisibility(
        visible = animVisible,
        enter = scaleIn(initialScale = 0.85f, animationSpec = tween(250)) +
                fadeIn(animationSpec = tween(200)),
        exit = scaleOut(targetScale = 0.85f, animationSpec = tween(200)) +
                fadeOut(animationSpec = tween(150))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ViewerBackground)
        ) {
        if (!imagesReady) {
            // 加载中状态（避免初始空页造成的"卡顿"感）
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "加载中…",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else if (images.isEmpty()) {
            // 空状态（相册确实无图片）
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无图片",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // 图片 Pager
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = Constants.PRELOAD_FORWARD_PAGES,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val image = images.getOrNull(page)
                if (image != null) {
                    ZoomableImage(
                        image = image,
                        scale = if (page == currentIndex) scale else 1f,
                        onScaleChange = { viewModel.setScale(it) },
                        onDoubleTap = { viewModel.toggleZoom() },
                        onSingleTap = { viewModel.toggleUiVisibility() }
                    )
                }
            }
        }

        // UI 覆盖层
        FullScreenOverlay(
            isVisible = isUiVisible,
            currentIndex = currentIndex,
            totalCount = images.size,
            isPlaying = isPlaying,
            onBack = {
                viewModel.stopPlaying()
                onBack()
            },
            onTogglePlay = { viewModel.togglePlay() },
            onSeekTo = { viewModel.goToImage(it) }
        )
        } // end AnimatedVisibility Box
    } // end AnimatedVisibility
}

private const val TAG = "FullScreenViewer"
