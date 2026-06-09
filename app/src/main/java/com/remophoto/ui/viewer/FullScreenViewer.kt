package com.remophoto.ui.viewer

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remophoto.RemoPhotoApp
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏浏览页面
 *
 * 功能：
 * - HorizontalPager 左右滑动切换图片
 * - 双指缩放 1×~5×（ZoomableImage）
 * - 双击 FIT↔CROP 切换
 * - 单击区域翻页：左侧1/3→上一张、右侧2/3→下一张、中间→显隐UI
 * - 音量键翻页（可设置关闭）
 * - 鼠标滚轮翻页
 * - 长按操作菜单（分享、详细信息、设为封面）
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
    val context = LocalContext.current
    val app = context.applicationContext as RemoPhotoApp
    val settingsRepository = app.dependencyContainer.settingsRepository
    val albumCoverManager = app.dependencyContainer.albumCoverManager

    val viewModel: FullScreenViewModel = viewModel()

    val images by viewModel.images.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val scale by viewModel.scale.collectAsState()
    val isUiVisible by viewModel.isUiVisible.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playIntervalMs by viewModel.playIntervalMs.collectAsState()
    val intervalSeconds = (playIntervalMs / 1000L).toInt()

    // 设置读取
    var useVolumeKeys by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        settingsRepository.useVolumeKeys.collect { useVolumeKeys = it }
    }

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

    // 全屏沉浸
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

    // ===== 长按菜单状态 =====
    var showLongPressMenu by remember { mutableStateOf(false) }
    var showImageDetail by remember { mutableStateOf(false) }
    val currentImage = viewModel.currentImage

    // 当前图片格式化信息
    val imageDetailText = remember(currentImage) {
        if (currentImage == null) ""
        else buildString {
            append("文件名: ${currentImage.fileName}\n")
            append("大小: ${formatFileSize(currentImage.fileSize)}\n")
            append("分辨率: ${currentImage.width} × ${currentImage.height}\n")
            append("修改日期: ${formatDate(currentImage.lastModified)}\n")
            append("格式: ${currentImage.mimeType}\n")
            append("路径: ${currentImage.filePath}")
        }
    }

    // 从小到大的扩张动画
    var animVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        animVisible = true
        AppLogger.d(TAG, "缩放动画已触发")
    }

    // ===== 焦点管理（用于音量键拦截） =====
    val focusRequester = remember { FocusRequester() }
    // 延迟请求焦点，等 AnimatedVisibility 展开后 focus 目标节点就绪
    LaunchedEffect(animVisible) {
        if (animVisible) {
            try {
                // 给 AnimatedVisibility 动画时间完成（250ms tween）
                delay(300L)
                focusRequester.requestFocus()
                AppLogger.d(TAG, "焦点请求成功")
            } catch (e: Exception) {
                AppLogger.w(TAG, "焦点请求失败: ${e.message}")
            }
        }
    }

    // 获取屏幕宽度用于点击区域判断
    var screenWidthPx by remember { mutableIntStateOf(0) }

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
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (!useVolumeKeys || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.VolumeUp -> {
                            AppLogger.d(TAG, "音量键+ → 上一张")
                            viewModel.previousImage()
                            true
                        }
                        Key.VolumeDown -> {
                            AppLogger.d(TAG, "音量键- → 下一张")
                            viewModel.nextImage()
                            true
                        }
                        else -> false
                    }
                }
        ) {
        if (!imagesReady) {
            // 加载中状态
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
                        onSingleTap = { tapOffset ->
                            // 点击区域翻页
                            val tapX = tapOffset.x
                            val thirdWidth = screenWidthPx / 3f
                            when {
                                tapX < thirdWidth -> {
                                    AppLogger.d(TAG, "点击左侧区域 → 上一张")
                                    viewModel.previousImage()
                                }
                                tapX > thirdWidth * 2 -> {
                                    AppLogger.d(TAG, "点击右侧区域 → 下一张")
                                    viewModel.nextImage()
                                }
                                else -> {
                                    AppLogger.d(TAG, "点击中间区域 → 切换 UI")
                                    viewModel.toggleUiVisibility()
                                }
                            }
                        },
                        onLongPress = { _ ->
                            AppLogger.d(TAG, "长按 → 显示菜单")
                            showLongPressMenu = true
                        },
                        onScrollUp = { viewModel.previousImage() },
                        onScrollDown = { viewModel.nextImage() },
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { coords ->
                                // 记录屏幕宽度用于点击区域判断
                                // 使用整个 pager 页面宽度（而非实际屏幕）
                            }
                    )
                }
            }
        }

        // 屏幕宽度测量（用于点击区域翻页）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { coords ->
                    screenWidthPx = coords.width
                }
        )

        // UI 覆盖层
        FullScreenOverlay(
            isVisible = isUiVisible,
            currentIndex = currentIndex,
            totalCount = images.size,
            isPlaying = isPlaying,
            intervalSeconds = intervalSeconds,
            onBack = {
                viewModel.stopPlaying()
                onBack()
            },
            onTogglePlay = { viewModel.togglePlay() },
            onIntervalChange = { sec -> viewModel.setPlayInterval(sec * 1000L) },
            onSeekTo = { viewModel.goToImage(it) }
        )
        } // end Box

        // ===== 长按菜单弹窗 =====
        if (showLongPressMenu && currentImage != null) {
            AlertDialog(
                onDismissRequest = { showLongPressMenu = false },
                title = { Text("图片操作", color = Color.White) },
                text = {
                    Column {
                        TextButton(
                            onClick = {
                                showLongPressMenu = false
                                // 分享图片
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = currentImage.mimeType ?: "image/*"
                                    val file = File(currentImage.filePath)
                                    if (file.exists()) {
                                        try {
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        } catch (e: Exception) {
                                            // FileProvider 未配置时使用文件路径
                                            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
                                        }
                                    }
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "分享图片"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📤 分享", color = Color.White, fontSize = 16.sp)
                        }

                        TextButton(
                            onClick = {
                                showLongPressMenu = false
                                showImageDetail = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("ℹ️ 详细信息", color = Color.White, fontSize = 16.sp)
                        }

                        TextButton(
                            onClick = {
                                showLongPressMenu = false
                                // 设为封面
                                viewModel.setAsCover(currentImage, albumCoverManager)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🖼️ 设为封面", color = Color.White, fontSize = 16.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLongPressMenu = false }) {
                        Text("取消", color = Color.White.copy(alpha = 0.6f))
                    }
                },
                containerColor = Color(0xFF2D2D2D),
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }

        // ===== 图片详情弹窗 =====
        if (showImageDetail && currentImage != null) {
            AlertDialog(
                onDismissRequest = { showImageDetail = false },
                title = { Text("图片详情", color = Color.White) },
                text = {
                    Text(
                        text = imageDetailText,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showImageDetail = false }) {
                        Text("关闭", color = Color.White.copy(alpha = 0.6f))
                    }
                },
                containerColor = Color(0xFF2D2D2D),
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }
    } // end AnimatedVisibility
}

/**
 * 格式化文件大小
 */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return "%.1f %s".format(size, units[unitIndex])
}

/**
 * 格式化日期
 */
private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0) return "未知"
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sdf.format(Date(timestamp))
    } catch (e: Exception) {
        "未知"
    }
}

private const val TAG = "FullScreenViewer"
