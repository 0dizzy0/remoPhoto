package com.remophoto.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 分页导航组件
 *
 * 底部页码导航：[<] 1 … 5 [6] 7 … 20 [>]
 * 点击当前页码区域弹出输入框跳转。
 *
 * @param currentPage 当前页码（1-based）
 * @param totalPages 总页数
 * @param onPageChange 页码变化回调
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
    var jumpPageInput by remember { mutableStateOf("") }

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
            Text("<", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 智能页码显示
        val pages = generatePageNumbers(currentPage, totalPages)
        pages.forEach { page ->
            when (page) {
                -1 -> {
                    // 省略号
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                else -> {
                    val isCurrent = page == currentPage
                    TextButton(
                        onClick = {
                            if (page != currentPage) onPageChange(page)
                            else {
                                showJumpDialog = true
                                jumpPageInput = ""
                            }
                        }
                    ) {
                        Text(
                            text = "$page",
                            style = if (isCurrent) MaterialTheme.typography.titleMedium
                                    else MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 下一页
        TextButton(
            onClick = { if (currentPage < totalPages) onPageChange(currentPage + 1) },
            enabled = currentPage < totalPages
        ) {
            Text(">", style = MaterialTheme.typography.titleMedium)
        }
    }

    // 跳页弹窗
    if (showJumpDialog) {
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = { Text("跳转到页") },
            text = {
                Column {
                    Text(
                        text = "输入页码 (1 - $totalPages)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = jumpPageInput,
                        onValueChange = { value ->
                            // 仅允许数字
                            if (value.all { it.isDigit() }) {
                                jumpPageInput = value
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        placeholder = { Text("页码") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val page = jumpPageInput.toIntOrNull()
                        if (page != null && page in 1..totalPages) {
                            onPageChange(page)
                            showJumpDialog = false
                        }
                    }
                ) {
                    Text("跳转")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 生成智能页码列表
 *
 * 规则：
 * - 总页数 ≤ 7 → 显示全部
 * - 否则显示：1 … 中间区域 … N
 * - -1 表示省略号
 */
private fun generatePageNumbers(current: Int, total: Int): List<Int> {
    if (total <= 7) {
        return (1..total).toList()
    }

    val pages = mutableListOf<Int>()

    // 始终显示第 1 页
    pages.add(1)

    if (current > 3) {
        pages.add(-1) // 省略号
    }

    // 中间区域：[current-1, current, current+1]
    val start = maxOf(2, current - 1)
    val end = minOf(total - 1, current + 1)
    for (i in start..end) {
        pages.add(i)
    }

    if (current < total - 2) {
        pages.add(-1) // 省略号
    }

    // 始终显示最后一页
    if (total > 1) {
        pages.add(total)
    }

    return pages
}
