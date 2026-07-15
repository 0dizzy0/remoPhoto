package com.remophoto.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.remophoto.RemoPhotoApp
import com.remophoto.domain.model.Album
import com.remophoto.data.remote.isRemoteMediaAddress
import com.remophoto.data.remote.remoteMediaCacheKey
import com.remophoto.util.AppLogger

/**
 * 相册卡片组件
 *
 * 支持两种布局模式：
 * - compact=true：双列网格模式（缩略图 + 名称）
 * - compact=false：单列列表模式（缩略图 + 名称 + 图片数量 + 子相册深度缩进）
 *
 * 多选模式：
 * - 长按进入多选，显示 Checkbox 叠加层
 * - 选中状态显示 primary 色边框
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCard(
    album: Album,
    compact: Boolean = true,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as RemoPhotoApp
    val coverLoader = if (album.coverImagePath?.isRemoteMediaAddress() == true) {
        app.dependencyContainer.remoteThumbnailLoader
    } else {
        app.dependencyContainer.thumbnailImageLoader
    }
    val coverRequest = remember(album.id, album.coverImagePath) {
        ImageRequest.Builder(context)
            .data(album.coverImagePath)
            .size(512)
            .crossfade(false)
            .apply {
                album.coverImagePath?.takeIf { it.isRemoteMediaAddress() }?.let { path ->
                    memoryCacheKey(path.remoteMediaCacheKey(":cover"))
                    diskCacheKey(path.remoteMediaCacheKey(":cover"))
                }
            }
            .build()
    }
    val indentPadding = if (!compact) (album.depth * 24).dp else 0.dp
    val cardShape = MaterialTheme.shapes.medium
    // 预提取多选边框 Modifier，避免重组时重复创建
    val primaryColor = MaterialTheme.colorScheme.primary
    val selectionBorder = remember(selected, primaryColor) {
        if (selected) Modifier.border(2.dp, primaryColor, cardShape)
        else Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentPadding, top = 4.dp, end = 4.dp, bottom = 4.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .then(selectionBorder)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            shape = cardShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            if (compact) {
                // 双列网格模式
                Column {
                    // 缩略图区域
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (album.coverImagePath != null) {
                            AsyncImage(
                                model = coverRequest,
                                imageLoader = coverLoader,
                                contentDescription = "相册封面：${album.name}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                onSuccess = {
                                    AppLogger.d(TAG, "封面加载成功: album=\"${album.name}\", path=${album.coverImagePath}")
                                },
                                onError = { error ->
                                    AppLogger.w(TAG,
                                        "封面加载失败: album=\"${album.name}\", " +
                                        "path=${album.coverImagePath}, error=${error.result.throwable?.message}"
                                    )
                                }
                            )
                        } else {
                            AppLogger.w(TAG, "封面路径为空: album=\"${album.name}\", id=${album.id}, imageCount=${album.imageCount}")
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "🖼️",
                                        style = MaterialTheme.typography.displaySmall
                                    )
                                }
                            }
                        }
                    }
                    // 名称 + 数量
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = album.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (album.imageCount > 0) {
                            Text(
                                text = "${album.imageCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // 单列列表模式
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 缩略图
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(MaterialTheme.shapes.small),
                        contentAlignment = Alignment.Center
                    ) {
                        if (album.coverImagePath != null) {
                            AsyncImage(
                                model = coverRequest,
                                imageLoader = coverLoader,
                                contentDescription = "相册封面：${album.name}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                onSuccess = {
                                    AppLogger.d(TAG, "封面加载成功: album=\"${album.name}\", path=${album.coverImagePath}")
                                },
                                onError = { error ->
                                    AppLogger.w(TAG,
                                        "封面加载失败: album=\"${album.name}\", " +
                                        "path=${album.coverImagePath}, error=${error.result.throwable?.message}"
                                    )
                                }
                            )
                        } else {
                            AppLogger.w(TAG, "封面路径为空(list): album=\"${album.name}\", id=${album.id}, imageCount=${album.imageCount}")
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("🖼️")
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = album.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "${album.imageCount} 张图片",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (album.children.isNotEmpty()) {
                                Text(
                                    text = "${album.children.size} 子相册",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        // 多选复选框叠加层
        if (selectionMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shadowElevation = 2.dp
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() }
                )
            }
        }
    }
}

private const val TAG = "AlbumCard"
