package com.remophoto.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角形状定义
 *
 * 满足需求：按钮和边框尽量使用圆角。
 * - small: 按钮、标签、输入框等小型控件
 * - medium: 卡片、相册缩略图
 * - large: 对话框、底部弹出面板
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)
