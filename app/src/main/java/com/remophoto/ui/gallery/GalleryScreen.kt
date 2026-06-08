package com.remophoto.ui.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remophoto.domain.model.ImageItem
import com.remophoto.ui.components.ImageThumbnail

/**
 * 图片网格页面
 *
 * 显示相册内所有图片的缩略图：
 * - 双列网格（默认）/ 单列列表切换
 * - Coil 异步加载缩略图
 * - 点击图片 → 全屏浏览
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    albumId: Long,
    albumName: String = "相册",
    onImageClick: (Int) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val viewModel: GalleryViewModel = viewModel()

    val images by viewModel.images.collectAsState()
    val album by viewModel.album.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()

    LaunchedEffect(albumId) {
        viewModel.loadAlbumAndImages(albumId)
    }

    val displayName = album?.name ?: albumName

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    TextButton(onClick = onBack) {
                        Text("← 返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleLayoutMode() }) {
                        Icon(
                            imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = if (isGridView) "列表模式" else "网格模式"
                        )
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            images.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "🖼️",
                            style = MaterialTheme.typography.displayLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "暂无图片",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            isGridView -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(images, key = { it.id }) { image ->
                        val index = images.indexOf(image)
                        ImageThumbnail(
                            image = image,
                            onClick = { onImageClick(index) }
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(images, key = { it.id }) { image ->
                        val index = images.indexOf(image)
                        ImageListItem(
                            image = image,
                            onClick = { onImageClick(index) }
                        )
                    }
                }
            }
        }
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
            // 左侧缩略图
            ImageThumbnail(
                image = image,
                onClick = {},
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            // 右侧信息
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
