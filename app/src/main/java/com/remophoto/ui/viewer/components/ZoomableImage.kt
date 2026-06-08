package com.remophoto.ui.viewer.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.remophoto.domain.model.ImageItem
import com.remophoto.util.Constants
import kotlin.math.abs

/**
 * 可缩放图片组件
 *
 * 手势策略：
 * - scale == 1f + 单指 → 不消费，HorizontalPager 处理滑动
 * - scale == 1f + 双指 → 消费，pinch-to-zoom
 * - scale > 1f + 双指 → 消费，缩放
 * - scale > 1f + 单指拖拽 → 消费，平移（限边界内）
 * - scale > 1f + 单指轻点（无拖拽）→ 不消费，透传给 detectTapGestures 处理双击
 *
 * 关键：使用 rememberUpdatedState 读取最新 scale，避免 pointerInput 键变化导致手势重启。
 */
@Composable
fun ZoomableImage(
    image: ImageItem,
    scale: Float,
    onScaleChange: (Float) -> Unit,
    onDoubleTap: () -> Unit,
    onSingleTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentScale by rememberUpdatedState(scale)

    // 平移偏移量
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // 回到 1× 时重置偏移
    LaunchedEffect(scale) {
        if (scale <= 1f) {
            offsetX = 0f
            offsetY = 0f
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 双击和单击
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onDoubleTap() },
                    onTap = { onSingleTap() }
                )
            }
            // 缩放和平移
            .pointerInput(Unit) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)

                    var previousCentroid = firstDown.position
                    var myScale = currentScale
                    var totalDragDistance = 0f

                    do {
                        val event = awaitPointerEvent()
                        val changes = event.changes

                        if (changes.size >= 2) {
                            totalDragDistance += 100f
                            val zoomChange = event.calculateZoom()
                            val newScale = (myScale * zoomChange)
                                .coerceIn(Constants.MIN_SCALE, Constants.MAX_SCALE)
                            onScaleChange(newScale)
                            myScale = newScale
                            changes.forEach { it.consume() }
                        } else if (myScale > Constants.MIN_SCALE + 0.01f) {
                            val change = changes.first()
                            val pan = change.position - previousCentroid

                            if (change.pressed) {
                                offsetX += pan.x
                                offsetY += pan.y
                                totalDragDistance += abs(pan.x) + abs(pan.y)
                            }
                            previousCentroid = change.position

                            if (totalDragDistance > 20f) {
                                changes.forEach { it.consume() }
                            }
                        }

                    } while (changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(image.filePath)
                    .crossfade(false)
                    .build(),
                contentDescription = image.fileName,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
                contentScale = ContentScale.Fit
            )
    }
}

/**
 * 简单的全屏图片组件（无缩放，用于 HorizontalPager 中）
 */
@Composable
fun FullScreenImage(
    image: ImageItem,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(image.filePath)
                .crossfade(false)
                .build(),
            contentDescription = image.fileName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}
