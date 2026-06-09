package com.remophoto.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remophoto.domain.model.SortOrder

/**
 * 设置页面
 *
 * 提供全局偏好设置：
 * - 主题模式（浅色/深色/跟随系统）
 * - 默认排序方式
 * - 每页相册数量
 * - 自动播放间隔
 * - 音量键翻页开关
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val sortOrder by viewModel.defaultSortOrder.collectAsState()
    val albumsPerPage by viewModel.albumsPerPage.collectAsState()
    val slideshowInterval by viewModel.slideshowInterval.collectAsState()
    val useVolumeKeys by viewModel.useVolumeKeys.collectAsState()
    val totalImageCount by viewModel.totalImageCount.collectAsState()
    val totalStorageSize by viewModel.totalStorageSize.collectAsState()

    // 各下拉菜单展开状态
    var showThemeMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showIntervalMenu by remember { mutableStateOf(false) }
    var showPageCountMenu by remember { mutableStateOf(false) }
    var showPageInputDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("← 返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ===== 外观 =====
            SettingsSectionHeader("外观")

            // 主题模式
            SettingsRow(
                label = "主题模式",
                value = themeModeDisplayName(themeMode)
            ) {
                showThemeMenu = true
            }
            DropdownMenu(
                expanded = showThemeMenu,
                onDismissRequest = { showThemeMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("☀️ 浅色") },
                    onClick = { viewModel.setThemeMode("light"); showThemeMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("🌙 深色") },
                    onClick = { viewModel.setThemeMode("dark"); showThemeMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("📱 跟随系统") },
                    onClick = { viewModel.setThemeMode("system"); showThemeMenu = false }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ===== 浏览 =====
            SettingsSectionHeader("浏览")

            // 默认排序
            Box {
                SettingsRow(
                    label = "默认排序",
                    value = sortOrder.displayName
                ) {
                    showSortMenu = true
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    SortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(order.displayName) },
                            onClick = { viewModel.setDefaultSortOrder(order); showSortMenu = false }
                        )
                    }
                }
            }

            // 每页相册数量
            Box {
                SettingsRow(
                    label = "每页相册数量",
                    value = "$albumsPerPage 个"
                ) {
                    showPageCountMenu = true
                }
                DropdownMenu(
                    expanded = showPageCountMenu,
                    onDismissRequest = { showPageCountMenu = false }
                ) {
                    listOf(5, 10, 20, 30, 50, 100).forEach { count ->
                        DropdownMenuItem(
                            text = { Text("$count 个") },
                            onClick = { viewModel.setAlbumsPerPage(count); showPageCountMenu = false }
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ===== 全屏浏览 =====
            SettingsSectionHeader("全屏浏览")

            // 自动播放间隔
            Box {
                SettingsRow(
                    label = "自动播放间隔",
                    value = "${slideshowInterval}s"
                ) {
                    showIntervalMenu = true
                }
                DropdownMenu(
                    expanded = showIntervalMenu,
                    onDismissRequest = { showIntervalMenu = false }
                ) {
                    listOf(1, 3, 5, 10, 30, 60).forEach { sec ->
                        DropdownMenuItem(
                            text = { Text("${sec}s") },
                            onClick = { viewModel.setSlideshowInterval(sec); showIntervalMenu = false }
                        )
                    }
                }
            }

            // 音量键翻页
            SettingsSwitchRow(
                label = "音量键翻页",
                description = "全屏时音量+上一张/音量-下一张",
                checked = useVolumeKeys,
                onCheckedChange = { viewModel.setUseVolumeKeys(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ===== 存储 =====
            SettingsSectionHeader("存储空间")

            SettingsInfoRow(
                label = "已索引图片",
                value = "$totalImageCount 张"
            )

            SettingsInfoRow(
                label = "占用空间",
                value = formatFileSize(totalStorageSize)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ===== 关于 =====
            SettingsSectionHeader("关于")

            SettingsInfoRow(
                label = "版本",
                value = "1.0.0-alpha.2"
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ===== 组件 =====

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsRow(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "›",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun themeModeDisplayName(mode: String): String = when (mode) {
    "light" -> "☀️ 浅色"
    "dark" -> "🌙 深色"
    else -> "📱 跟随系统"
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return "%.1f %s".format(size, units[unitIndex])
}
