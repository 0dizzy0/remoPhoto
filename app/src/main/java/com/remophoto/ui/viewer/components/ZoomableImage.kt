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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.remophoto.domain.model.ImageItem
import com.remophoto.util.AppLogger
import com.remophoto.util.Constants
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 可缩放图片组件
 *
 * 手势策略：单一 pointerInput 协作处理所有手势
 * - 双指 → pinch-to-zoom（消费）
 * - 缩放后单指拖拽 → 平移（消费，距离>20px 后拦截 pager 滑动）
 * - scale==1 + 单指轻点 → 透传给 detectTapGestures（单击/双击）
 * - scale==1 + 单指滑动 → 透传给 HorizontalPager
 * - 鼠标滚轮 → scale==1 时翻页，scale>1 时缩放
 *
 * 关键：使用 awaitPointerEventScope 替代多个 pointerInput，避免手势竞争。
 */
@Composable
fun ZoomableImage(
    image: ImageItem,
    scale: Float,
    onScaleChange: (Float) -> Unit,
    onDoubleTap: () -> Unit,
    onSingleTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit = {},
    onScrollUp: () -> Unit = {},
    onScrollDown: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentScale by rememberUpdatedState(scale)

    // 平移偏移量
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // 组件尺寸（用于平移边界计算）
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // 回到 1× 时重置偏移
    LaunchedEffect(scale) {
        if (scale <= 1f) {
            offsetX = 0f
            offsetY = 0f
        }
    }

    /**
     * 约束平移偏移量在图片边界内
     * 图片以 ContentScale.Fit 显示，计算缩放后图片实际尺寸
     */
    fun clampOffset(imgW: Int, imgH: Int) {
        if (containerSize.width <= 0 || containerSize.height <= 0) return
        if (imgW <= 0 || imgH <= 0) return

        val viewW = containerSize.width.toFloat()
        val viewH = containerSize.height.toFloat()

        // 计算 Fit 缩放后的图片显示尺寸
        val fitScale = min(viewW / imgW, viewH / imgH)
        val displayW = imgW * fitScale * currentScale
        val displayH = imgH * fitScale * currentScale

        // 最大允许的平移量（图片边缘不超出视图中心）
        val maxOffsetX = max(0f, (displayW - viewW) / 2f)
        val maxOffsetY = max(0f, (displayH - viewH) / 2f)

        offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
        offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
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

                        // 鼠标滚轮事件
                        if (event.type == PointerEventType.Scroll) {
                            if (myScale <= Constants.MIN_SCALE + 0.01f) {
                                // scale==1: 滚轮用于翻页
                                val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (scrollDelta > 0) {
                                    onScrollDown()
                                } else if (scrollDelta < 0) {
                                    onScrollUp()
                                }
                                gestureConsumed = true
                                changes.forEach { it.consume() }
                            } else {
                                // scale>1: 滚轮用于缩放
                                val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                val zoomFactor = if (scrollDelta > 0) 0.95f else 1.05f
                                val newScale = (myScale * zoomFactor)
                                    .coerceIn(Constants.MIN_SCALE, Constants.MAX_SCALE)
                                onScaleChange(newScale)
                                myScale = newScale
                                gestureConsumed = true
                                changes.forEach { it.consume() }
                            }
                        } else if (changes.size >= 2 || myScale > Constants.MIN_SCALE + 0.01f) {
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
                                    // 每次平移后约束边界
                                    clampOffset(image.width, image.height)
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
                    onTap = { offset -> onSingleTap(offset) },
                    onLongPress = { offset -> onLongPress(offset) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(image.filePath)
                .crossfade(false)
                .size(2048)
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
