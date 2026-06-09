package com.remophoto.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.remophoto.domain.model.Album
import com.remophoto.util.AppLogger

/**
 * 相册卡片组件
 *
 * 支持两种布局模式：
 * - compact=true：双列网格模式（缩略图 + 名称）
 * - compact=false：单列列表模式（缩略图 + 名称 + 图片数量 + 子相册深度缩进）
 */
@Composable
fun AlbumCard(
    album: Album,
    compact: Boolean = true,
    onClick: () -> Unit = {}
) {
    val indentPadding = if (!compact) (album.depth * 24).dp else 0.dp

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentPadding, top = 4.dp, end = 4.dp, bottom = 4.dp),
        shape = MaterialTheme.shapes.medium,
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
                            model = album.coverImagePath,
                            contentDescription = album.name,
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
                // 封面文件名（调试用：显示哪张图被设为封面）
                if (album.coverImagePath != null) {
                    val coverFileName = extractFileName(album.coverImagePath)
                    Text(
                        text = "ID:${album.id} 封面: $coverFileName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
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
                        maxLines = 1,
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
                            model = album.coverImagePath,
                            contentDescription = album.name,
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
                        maxLines = 1,
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
}

/** 从 content URI 中提取文件名 */
private fun extractFileName(uriPath: String): String {
    return try {
        val raw = uriPath.substringAfterLast('/')
        java.net.URLDecoder.decode(raw, "UTF-8")
    } catch (_: Exception) {
        uriPath.substringAfterLast('/')
    }
}

private const val TAG = "AlbumCard"
