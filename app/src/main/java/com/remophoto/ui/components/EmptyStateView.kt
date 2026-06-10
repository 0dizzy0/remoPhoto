package com.remophoto.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 空状态视图组件
 *
 * 统一的无内容状态提示，用于：
 * - 空仓库列表
 * - 空相册列表
 * - 分类筛选无结果
 * - 相册内无图片
 *
 * @param icon 图标（emoji 字符，如 "📷"）
 * @param title 主标题
 * @param subtitle 副标题/说明文字
 * @param actionLabel 操作按钮文字（如"添加仓库"），null 则不显示
 * @param onAction 操作按钮回调
 * @param modifier 布局修饰符
 */
@Composable
fun EmptyStateView(
    icon: String,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = icon,
                style = MaterialTheme.typography.displayLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
