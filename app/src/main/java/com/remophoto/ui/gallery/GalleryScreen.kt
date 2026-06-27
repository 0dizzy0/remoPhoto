package com.remophoto.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.onGloballyPositioned
import com.remophoto.domain.model.ImageItem
import com.remophoto.ui.components.DraggableScrollbar
import com.remophoto.ui.components.EmptyStateView
import com.remophoto.ui.components.ImageThumbnail
import com.remophoto.util.AppLogger

/**
 * 图片网格页面
 *
 * 显示相册内所有图片的缩略图：
 * - 双列网格（默认）/ 单列列表切换
 * - Coil 异步加载缩略图
 * - 点击图片 → 全屏浏览
 *
 * 使用 Box 布局替代 Scaffold —— 彻底绕过 Scaffold 对 window inset 的自动响应，
 * 从而消除全屏进出时的布局跳动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    albumId: Long,
    albumName: String = "相册",
    onImageClick: (Int) -> Unit = {},
    onBack: () -> Unit = {},
    onAlbumSettingsClick: (Long) -> Unit = {}
) {
    val viewModel: GalleryViewModel = viewModel()

    val images by viewModel.images.collectAsState()
    val album by viewModel.album.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()

    LaunchedEffect(albumId) {
        viewModel.loadAlbumAndImages(albumId)
    }

    // 系统返回键：回到相册列表
    BackHandler { onBack() }

    val displayName = album?.name ?: albumName

    // ===== 日志：GalleryScreen 生命周期 =====
    DisposableEffect(albumId) {
        AppLogger.i(TAG, "🟢 GalleryScreen 进入组合: albumId=$albumId, displayName=$displayName")
        onDispose {
            AppLogger.i(TAG, "🔴 GalleryScreen 离开组合: albumId=$albumId")
        }
    }

    // 防重复导航保护（切换相册时重置）
    var isNavigating by remember { mutableStateOf(false) }
    LaunchedEffect(albumId) {
        isNavigating = false
    }

    // Box 布局：绕过 Scaffold 的 window inset 自动响应，彻底消除全屏跳动
    Box(modifier = Modifier
        .fillMaxSize()
        .onGloballyPositioned { coords ->
            AppLogger.i(TAG, "🖼️ 外层Box: size=${coords.size}, isAttached=${coords.isAttached}")
        }
    ) {
        // 内容区（TopAppBar 64dp + 状态栏区域由 edge-to-edge 处理）
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(top = 64.dp)
            .onGloballyPositioned { coords ->
                AppLogger.i(TAG, "📦 内容区: size=${coords.size}, isAttached=${coords.isAttached}")
            }
        ) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                images.isEmpty() -> {
                    EmptyStateView(
                        icon = "🖼️",
                        title = "暂无图片",
                        modifier = Modifier.fillMaxSize()
                    )
                }
                isGridView -> {
                    val gridState = rememberLazyGridState()
                    val columns = 2
                    val currentRow by remember(gridState) {
                        derivedStateOf { gridState.firstVisibleItemIndex / columns }
                    }
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coords ->
                            AppLogger.i(TAG, "📦 网格区域: size=${coords.size}")
                        }
                    ) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            itemsIndexed(
                                images,
                                key = { _, item -> item.id },
                                contentType = { _, _ -> "image_thumb" }
                            ) { index, image ->
                                ImageThumbnail(
                                    image = image,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f),
                                    onClick = {
                                        if (isNavigating) return@ImageThumbnail
                                        isNavigating = true
                                        AppLogger.i(TAG,
                                            "🖼️ 点击缩略图: albumId=$albumId, index=$index, fileName=${image.fileName}"
                                        )
                                        onImageClick(index)
                                    }
                                )
                            }
                        }
                        if (images.size > 20) {
                            DraggableScrollbar(
                                totalRows = (images.size + columns - 1) / columns,
                                currentRow = currentRow,
                                isScrollInProgress = gridState.isScrollInProgress,
                                onScrollToRow = { row -> gridState.scrollToItem(row * columns) },
                                modifier = Modifier.align(Alignment.CenterEnd)
                            )
                        }
                    }
                }
                else -> {
                    val listState = rememberLazyListState()
                    val currentRow by remember(listState) {
                        derivedStateOf { listState.firstVisibleItemIndex }
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(
                                images,
                                key = { _, item -> item.id },
                                contentType = { _, _ -> "image_list_item" }
                            ) { index, image ->
                                ImageListItem(
                                    image = image,
                                    onClick = {
                                        if (isNavigating) return@ImageListItem
                                        isNavigating = true
                                        AppLogger.i(TAG,
                                            "🖼️ 点击列表项: albumId=$albumId, index=$index, fileName=${image.fileName}"
                                        )
                                        onImageClick(index)
                                    }
                                )
                            }
                        }
                        if (images.size > 20) {
                            DraggableScrollbar(
                                totalRows = images.size,
                                currentRow = currentRow,
                                isScrollInProgress = listState.isScrollInProgress,
                                onScrollToRow = { row -> listState.scrollToItem(row) },
                                modifier = Modifier.align(Alignment.CenterEnd)
                            )
                        }
                    }
                }
            }
        }

        // TopAppBar 覆盖在内容上方，windowInsets=0 使其高度不受状态栏显隐影响
        TopAppBar(
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onGloballyPositioned { coords ->
                    AppLogger.i(TAG, "📐 TopAppBar: size=${coords.size}, isAttached=${coords.isAttached}")
                },
            title = {
                Column {
                    Text(displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (images.isNotEmpty()) {
                        Text(
                            text = "${images.size} 张图片",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            navigationIcon = {
                TextButton(onClick = {
                    AppLogger.i(TAG, "点击返回按钮: 图片网格 → 相册列表")
                    onBack()
                }) {
                    Text("← 返回")
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        AppLogger.i(TAG, "点击相册设置按钮 (albumId=$albumId)")
                        onAlbumSettingsClick(albumId)
                    },
                    modifier = Modifier.semantics { contentDescription = "相册设置" }
                ) {
                    Text("⚙️", modifier = Modifier.padding(4.dp))
                }
                IconButton(onClick = {
                    AppLogger.i(TAG, "点击布局切换按钮 (当前=${if (isGridView) "网格" else "列表"})")
                    viewModel.toggleLayoutMode()
                }) {
                    Icon(
                        imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                        contentDescription = if (isGridView) "列表模式" else "网格模式"
                    )
                }
            }
        )
    }
}

/**
 * 单列列表模式下的图片行
 */
@Composable
private fun ImageListItem(
    image: ImageItem,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ImageThumbnail(
                image = image,
                onClick = {},
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = image.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = image.displaySize,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (image.isAnimated) {
                    Text(
                        text = "GIF" + if (image.mimeType == "image/webp") "/WebP" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private const val TAG = "Gallery"
