package com.remophoto.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 分页导航组件（占位 — Phase 2 完善）
 *
 * 底部页码导航：[<] 1 2 3 ... 10 [>]
 * 点击页码区域弹出输入框跳转。
 */
@Composable
fun PageNavigator(
    currentPage: Int,
    totalPages: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (totalPages <= 1) return

    var showJumpDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 上一页
        TextButton(
            onClick = { if (currentPage > 1) onPageChange(currentPage - 1) },
            enabled = currentPage > 1
        ) {
            Text("<")
        }

        // 页码显示
        TextButton(onClick = { showJumpDialog = true }) {
            Text(
                text = "$currentPage / $totalPages",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // 下一页
        TextButton(
            onClick = { if (currentPage < totalPages) onPageChange(currentPage + 1) },
            enabled = currentPage < totalPages
        ) {
            Text(">")
        }
    }

    // 跳页对话框（Phase 2 实现）
    if (showJumpDialog) {
        // TODO: Phase 2 实现页码输入弹窗
        showJumpDialog = false
    }
}
