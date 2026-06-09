package com.remophoto.ui.albumlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
 * 单相册设置页
 *
 * 设置项：
 * - 相册信息（名称、路径、图片数）
 * - 自定义排序（覆盖全局设置）
 * - 分类关联（多选勾选）
 * - 封面管理（清除自定义封面）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumSettingsScreen(
    albumId: Long,
    onBack: () -> Unit = {},
    viewModel: AlbumSettingsViewModel = viewModel()
) {
    val album by viewModel.album.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val isCustomSort by viewModel.isCustomSort.collectAsState()
    val imageCount by viewModel.imageCount.collectAsState()
    val albumCategories by viewModel.albumCategories.collectAsState()
    val allCategories by viewModel.allCategories.collectAsState()

    var showSortMenu by remember { mutableStateOf(false) }

    LaunchedEffect(albumId) {
        viewModel.loadAlbum(albumId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(album?.name ?: "相册设置") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("← 返回")
                    }
                }
            )
        }
    ) { padding ->
        if (album == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // ===== 相册信息 =====
                SettingsSectionHeader("相册信息")

                SettingsInfoRow("名称", album!!.name)
                SettingsInfoRow("路径", album!!.directoryPath)
                SettingsInfoRow("图片数量", "$imageCount 张")

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // ===== 排序设置 =====
                SettingsSectionHeader("排序设置")

                // 使用全局/自定义切换
                SettingsSwitchRow(
                    label = "使用自定义排序",
                    description = if (isCustomSort) "已覆盖全局排序" else "使用全局默认排序",
                    checked = isCustomSort,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            viewModel.setCustomSortOrder(SortOrder.DEFAULT)
                        } else {
                            viewModel.setCustomSortOrder(null)
                        }
                    }
                )

                if (isCustomSort) {
                    Box {
                        SettingsRow(
                            label = "排序方式",
                            value = sortOrder?.displayName ?: ""
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
                                    onClick = {
                                        viewModel.setCustomSortOrder(order)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // ===== 分类关联 =====
                SettingsSectionHeader("分类标签")

                if (allCategories.isEmpty()) {
                    Text(
                        text = "暂无分类，请在分类管理中创建",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    val associatedIds = albumCategories.map { it.id }.toSet()
                    allCategories.forEach { category ->
                        val isChecked = category.id in associatedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleCategory(category.id) }
                                .padding(vertical = 8.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { viewModel.toggleCategory(category.id) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = category.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // ===== 封面管理 =====
                SettingsSectionHeader("封面")

                TextButton(
                    onClick = { viewModel.clearCustomCover() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("恢复自动选取封面")
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ===== 复用组件（与 SettingsScreen 中的一致） =====

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
    onClick: () -> Unit = {}
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
