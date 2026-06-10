package com.remophoto.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 可拖动快速滚动条
 *
 * - 拖拽实时跟随手指
 * - 24dp 触摸区
 * - 自动隐藏（停止 1.5s 后淡出）
 * - 颜色适配主题（使用 outline/primary 等高对比色）
 */
@Composable
fun DraggableScrollbar(
    totalRows: Int,
    currentRow: Int,
    isScrollInProgress: Boolean,
    onScrollToRow: suspend (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (totalRows <= 1) return

    val coroutineScope = rememberCoroutineScope()

    var isDragging by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(isScrollInProgress, isDragging) {
        if (isScrollInProgress || isDragging) {
            isVisible = true
        } else {
            delay(1500L)
            isVisible = false
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(300),
        label = "scrollbar_alpha"
    )

    val thumbFraction = (1f / totalRows).coerceIn(0.05f, 0.15f)

    val scrollProgress = if (totalRows > 1) {
        (currentRow.toFloat() / (totalRows - 1).toFloat()).coerceIn(0f, 1f)
    } else 0f

    // 拖拽时滑块跟随手指，非拖拽时由 scrollProgress 驱动
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    val effectiveProgress = if (isDragging && dragFraction >= 0f) dragFraction else scrollProgress

    // 高对比色——浅色背景上深灰色可见，深色背景上浅灰色可见
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.70f)

    // 外层：24dp 触摸区
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(24.dp)
            .padding(vertical = 8.dp)
            .pointerInput(totalRows) {
                detectVerticalDragGestures(
                    onDragStart = {
                        isDragging = true
                        val f = if (totalRows > 1) currentRow.toFloat() / (totalRows - 1) else 0f
                        dragFraction = f.coerceIn(0f, 1f)
                    },
                    onDragEnd = { isDragging = false; dragFraction = -1f },
                    onDragCancel = { isDragging = false; dragFraction = -1f }
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.y / size.height).coerceIn(0f, 1f)
                    val targetRow = (dragFraction * (totalRows - 1)).toInt()
                    coroutineScope.launch { onScrollToRow(targetRow) }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // 内层：BoxWithConstraints 获取轨道真实像素高度，才能正确计算滑块偏移
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxHeight()
                .width(8.dp)
                .alpha(alpha)
                .clip(RoundedCornerShape(4.dp))
                .background(trackColor)
        ) {
            val density = LocalDensity.current
            val trackHeightPx = constraints.maxHeight.toFloat()
            val thumbHeightDp = with(density) { (trackHeightPx * thumbFraction).toDp() }
            val maxOffsetPx = (trackHeightPx * (1f - thumbFraction)).coerceAtLeast(0f)
            val yOffsetPx = (effectiveProgress * maxOffsetPx).toInt()

            // 滑块——用 offset 精确定位
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thumbHeightDp)
                    .offset { IntOffset(0, yOffsetPx) }
                    .background(thumbColor, RoundedCornerShape(4.dp))
            )
        }
    }
}
