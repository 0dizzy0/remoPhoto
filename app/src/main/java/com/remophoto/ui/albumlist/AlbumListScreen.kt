package com.remophoto.ui.albumlist

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 相册列表页面（占位 — Phase 1 实现）
 *
 * 显示所有相册的多层级树形列表，支持双列/单行切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumListScreen(
    onAlbumClick: (Long) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onRepositoryManagerClick: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("remoPhoto") },
                actions = {
                    IconButton(onClick = onRepositoryManagerClick) {
                        // 仓库管理图标（使用内置图标替代）
                        Text("📁", modifier = Modifier.padding(8.dp))
                    }
                    IconButton(onClick = onSettingsClick) {
                        Text("⚙️", modifier = Modifier.padding(8.dp))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "📷",
                    style = MaterialTheme.typography.displayLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "暂无相册",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "请先添加图片仓库",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
