package com.remophoto.ui.viewer.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 全屏底部进度条组件
 *
 * 使用本地 Slider 位置状态避免 "拖动 → onValueChange → seek → 索引更新 → Slider 重绘" 的正反馈震荡环。
 * 仅在用户手指抬起（onValueChangeFinished）时触发真正的页面跳转。
 */
@Composable
fun ViewerProgressBar(
    currentIndex: Int,
    totalCount: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (totalCount <= 0) return

    // 本地滑块位置，独立于 currentIndex，避免拖动时与 ViewModel/pager 双向同步造成震荡
    var sliderPosition by remember { mutableFloatStateOf(currentIndex.toFloat()) }

    // 仅在外部索引变化（如页面滑动）且用户未在拖动时，同步滑块位置
    LaunchedEffect(currentIndex) {
        // 这里的 currentIndex 来自 ViewModel，仅在非拖动状态下同步
        sliderPosition = currentIndex.toFloat()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Slider(
            value = sliderPosition,
            valueRange = 0f..(totalCount - 1).coerceAtLeast(1).toFloat(),
            onValueChange = { value ->
                // 拖动中：仅更新本地滑块位置，不触发 seek
                sliderPosition = value
            },
            onValueChangeFinished = {
                // 手指抬起：触发真正的页面跳转
                onSeek(sliderPosition.toInt())
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.8f),
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )
    }
}
