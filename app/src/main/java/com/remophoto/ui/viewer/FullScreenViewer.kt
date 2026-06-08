package com.remophoto.ui.viewer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remophoto.domain.model.ImageItem
import com.remophoto.ui.theme.ViewerBackground
import com.remophoto.ui.viewer.components.FullScreenImage
import com.remophoto.ui.viewer.components.FullScreenOverlay
import com.remophoto.ui.viewer.components.ZoomableImage
import com.remophoto.util.Constants
import kotlinx.coroutines.delay

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
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    val images by viewModel.images.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val scale by viewModel.scale.collectAsState()
    val isUiVisible by viewModel.isUiVisible.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

    // 隐藏状态栏和导航栏（全屏沉浸模式）
    DisposableEffect(Unit) {
        activity?.let {
            val controller = WindowCompat.getInsetsController(it.window, it.window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            activity?.let {
                val controller = WindowCompat.getInsetsController(it.window, it.window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 加载图片
    LaunchedEffect(albumId) {
        viewModel.loadImages(albumId, imageIndex)
    }

    val pagerState = rememberPagerState(
        initialPage = imageIndex,
        pageCount = { images.size.coerceAtLeast(1) }
    )

    // 页面导航：图片列表就绪后立即跳转到目标页
    LaunchedEffect(albumId, imageIndex, images.size) {
        if (images.isNotEmpty() && imageIndex in images.indices) {
            pagerState.scrollToPage(imageIndex)
            viewModel.goToImage(imageIndex)
        }
    }

    // 同步 pager → ViewModel（用户滑动 pager 时）
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != viewModel.currentIndex.value) {
            viewModel.goToImage(pagerState.currentPage)
        }
    }

    // 同步 ViewModel → pager（进度条 seek 时，使用 scrollToPage 瞬间跳转）
    LaunchedEffect(currentIndex) {
        if (currentIndex != pagerState.currentPage) {
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ViewerBackground)
    ) {
        if (images.isEmpty()) {
            // 空状态
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
    }
}
