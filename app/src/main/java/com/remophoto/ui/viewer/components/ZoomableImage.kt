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
 * 手势策略：单一 pointerInput 协作处理所有手势
 * - 双指 → pinch-to-zoom（消费）
 * - 缩放后单指拖拽 → 平移（消费，距离>20px 后拦截 pager 滑动）
 * - scale==1 + 单指轻点 → 透传给 detectTapGestures（单击/双击）
 * - scale==1 + 单指滑动 → 透传给 HorizontalPager
 *
 * 关键：使用 awaitPointerEventScope 替代多个 pointerInput，避免手势竞争。
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
            // 缩放+平移手势（内层，靠近内容，优先接收事件）
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var myScale = currentScale
                    var previousCentroid = down.position
                    var totalDragDistance = 0f
                    var gestureConsumed = false
                    var isUp = false

                    do {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        isUp = changes.all { !it.pressed }

                        if (isUp) break

                        if (changes.size >= 2 || myScale > Constants.MIN_SCALE + 0.01f) {
                            // 双指缩放 或 已缩放状态下的单指平移
                            gestureConsumed = true
                            if (changes.size >= 2) {
                                val zoomChange = event.calculateZoom()
                                val newScale = (myScale * zoomChange)
                                    .coerceIn(Constants.MIN_SCALE, Constants.MAX_SCALE)
                                onScaleChange(newScale)
                                myScale = newScale
                                changes.forEach { it.consume() }
                            } else {
                                // 单指平移（仅缩放后生效）
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
                        }
                        // scale==1 且单指 → 不消费，事件透传给 pager

                    } while (!isUp)

                    // 轻点（无拖拽且未缩放）→ 不消费
                    if (!gestureConsumed && totalDragDistance < 20f && myScale <= Constants.MIN_SCALE + 0.01f) {
                        // 不消费事件，让 detectTapGestures 接收
                    }
                }
            }
            // 轻点手势（外层，仅在缩放/平移未消费时生效）
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onDoubleTap() },
                    onTap = { onSingleTap() }
                )
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
