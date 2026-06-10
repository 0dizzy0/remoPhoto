package com.remophoto.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import com.remophoto.domain.model.ImageItem
import com.remophoto.util.AppLogger

/**
 * 图片缩略图组件
 *
 * 使用 Coil AsyncImage 加载缩略图，支持：
 * - 自适应比例（保持宽高比填充）
 * - 加载中/失败占位 UI
 * - 动图标记（GIF/WebP 标签）
 * - 可指定大小以限制解码尺寸
 */
@Composable
fun ImageThumbnail(
    image: ImageItem,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f)
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .clip(MaterialTheme.shapes.small),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // 记忆化请求：使用 id+filePath 作为缓存键，避免不同图片共享相同路径的缓存
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val app = ctx.applicationContext as com.remophoto.RemoPhotoApp
            val thumbLoader = app.dependencyContainer.thumbnailImageLoader

            val thumbRequest = remember(image.id, image.filePath) {
                ImageRequest.Builder(ctx)
                    .data(image.filePath)
                    .crossfade(true)
                    .size(300)
                    .precision(coil.size.Precision.INEXACT)  // 使用近似精度，更快解码
                    .memoryCacheKey(image.filePath + "_thumb")  // 精确缓存键
                    .listener(
                        onError = { _, result ->
                            AppLogger.e(TAG,
                                "缩略图加载失败: id=${image.id}, fileName=${image.fileName}, " +
                                "path=${image.filePath}, error=${result.throwable?.message}",
                                result.throwable
                            )
                        }
                    )
                    .build()
            }

            coil.compose.SubcomposeAsyncImage(
                model = thumbRequest,
                imageLoader = thumbLoader,
                contentDescription = image.fileName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📷", style = MaterialTheme.typography.titleLarge)
                    }
                },
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF3A1A1A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⚠️", style = MaterialTheme.typography.titleLarge)
                    }
                }
            )

            // 动图标记
            if (image.isAnimated) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = "GIF",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

private const val TAG = "Thumbnail"
