package com.remophoto.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remophoto.domain.model.SortOrder
import com.remophoto.ui.components.SettingsInfoRow
import com.remophoto.ui.components.SettingsRow
import com.remophoto.ui.components.SettingsSectionHeader
import com.remophoto.ui.components.SettingsSwitchRow
import com.remophoto.util.AppLogger

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
    val context = LocalContext.current

    // SAF 导出文件选择器
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportDatabase(context, uri)
        }
    }

    // SAF 导入文件选择器
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importDatabase(context, uri)
        }
    }

    val themeMode by viewModel.themeMode.collectAsState()
    val darkModeType by viewModel.darkModeType.collectAsState()
    val highContrast by viewModel.highContrast.collectAsState()
    val sortOrder by viewModel.defaultSortOrder.collectAsState()
    val albumsPerPage by viewModel.albumsPerPage.collectAsState()
    val slideshowInterval by viewModel.slideshowInterval.collectAsState()
    val useVolumeKeys by viewModel.useVolumeKeys.collectAsState()
    val totalImageCount by viewModel.totalImageCount.collectAsState()
    val totalStorageSize by viewModel.totalStorageSize.collectAsState()

    // 各下拉菜单展开状态
    var showThemeMenu by remember { mutableStateOf(false) }
    var showDarkModeTypeMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showIntervalMenu by remember { mutableStateOf(false) }
    var showPageCountMenu by remember { mutableStateOf(false) }
    var showPortMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    TextButton(onClick = {
                        AppLogger.i(TAG, "点击返回按钮")
                        onBack()
                    }) {
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
                    onClick = { AppLogger.i(TAG, "设置主题: light"); viewModel.setThemeMode("light"); showThemeMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("🌙 深色") },
                    onClick = { AppLogger.i(TAG, "设置主题: dark"); viewModel.setThemeMode("dark"); showThemeMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("📱 跟随系统") },
                    onClick = { AppLogger.i(TAG, "设置主题: system"); viewModel.setThemeMode("system"); showThemeMenu = false }
                )
            }

            // 深色背景类型（仅在深色或跟随系统时可见）
            if (themeMode != "light") {
                Box {
                    SettingsRow(
                        label = "深色背景类型",
                        value = darkModeTypeDisplayName(darkModeType)
                    ) {
                        showDarkModeTypeMenu = true
                    }
                    DropdownMenu(
                        expanded = showDarkModeTypeMenu,
                        onDismissRequest = { showDarkModeTypeMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("🔍 自动检测") },
                            onClick = { AppLogger.i(TAG, "设置深色背景: auto"); viewModel.setDarkModeType("auto"); showDarkModeTypeMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("⬛ 纯黑 (OLED)") },
                            onClick = { AppLogger.i(TAG, "设置深色背景: oled"); viewModel.setDarkModeType("oled"); showDarkModeTypeMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("◼️ 深灰 (LCD)") },
                            onClick = { AppLogger.i(TAG, "设置深色背景: lcd"); viewModel.setDarkModeType("lcd"); showDarkModeTypeMenu = false }
                        )
                    }
                }
            }

            // 高对比度
            SettingsSwitchRow(
                label = "高对比度",
                description = "提高文字与背景的对比度",
                checked = highContrast,
                onCheckedChange = { AppLogger.i(TAG, "切换高对比度: $it"); viewModel.setHighContrast(it) }
            )

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
                            onClick = { AppLogger.i(TAG, "设置排序: ${order.name}"); viewModel.setDefaultSortOrder(order); showSortMenu = false }
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
                            onClick = { AppLogger.i(TAG, "设置每页数量: $count"); viewModel.setAlbumsPerPage(count); showPageCountMenu = false }
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
                            onClick = { AppLogger.i(TAG, "设置播放间隔: ${sec}s"); viewModel.setSlideshowInterval(sec); showIntervalMenu = false }
                        )
                    }
                }
            }

            // 音量键翻页
            SettingsSwitchRow(
                label = "音量键翻页",
                description = "全屏时音量+上一张/音量-下一张",
                checked = useVolumeKeys,
                onCheckedChange = { AppLogger.i(TAG, "切换音量键翻页: $it"); viewModel.setUseVolumeKeys(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ===== 远程服务（Phase 4） =====
            SettingsSectionHeader("远程服务")

            val httpEnabled by viewModel.httpServerEnabled.collectAsState()
            val httpPort by viewModel.httpServerPort.collectAsState()
            val deviceName by viewModel.deviceName.collectAsState()
            val serverRunning by viewModel.serverRunning.collectAsState()

            // 开启/关闭开关
            SettingsSwitchRow(
                label = "允许远程访问",
                description = if (serverRunning) "服务运行中，局域网设备可浏览本机相册" else "开启后同一 WiFi 下的设备可访问本机图片",
                checked = serverRunning,
                onCheckedChange = { viewModel.toggleServer(context) }
            )

            // 端口选择（仅在未运行时可修改）
            Box {
                SettingsRow(
                    label = "服务端口",
                    value = "$httpPort"
                ) {
                    if (!serverRunning) showPortMenu = true
                }
                DropdownMenu(
                    expanded = showPortMenu,
                    onDismissRequest = { showPortMenu = false }
                ) {
                    listOf(8080, 8081, 8082, 9090).forEach { port ->
                        DropdownMenuItem(
                            text = { Text("$port") },
                            onClick = { viewModel.setHttpServerPort(port); showPortMenu = false }
                        )
                    }
                }
            }

            // 设备名称（始终可见，运行中灰选不可编辑）
            var editDeviceName by remember { mutableStateOf(deviceName) }
            // 同步 DataStore 中的 deviceName 到编辑状态（处理持久化恢复）
            LaunchedEffect(deviceName) {
                if (editDeviceName != deviceName) {
                    editDeviceName = deviceName
                }
            }
            OutlinedTextField(
                value = editDeviceName,
                onValueChange = { editDeviceName = it },
                label = { Text("设备名称") },
                placeholder = { Text("用于局域网内识别本设备") },
                singleLine = true,
                enabled = !serverRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                supportingText = if (serverRunning) {
                    { Text("停止服务后可修改") }
                } else null,
                trailingIcon = {
                    if (!serverRunning && editDeviceName != deviceName) {
                        IconButton(onClick = { viewModel.setDeviceName(editDeviceName) }) {
                            Icon(Icons.Default.Check, "保存")
                        }
                    }
                }
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

            Spacer(modifier = Modifier.height(8.dp))

            // Phase 4: 远程缓存
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SettingsSectionHeader("远程缓存")
            SettingsInfoRow(
                label = "缩略图缓存",
                value = viewModel.remoteThumbCacheSize()
            )
            SettingsInfoRow(
                label = "原图缓存",
                value = viewModel.remoteImageCacheSize()
            )
            OutlinedButton(
                onClick = {
                    AppLogger.i(TAG, "点击清除远程缓存")
                    viewModel.clearRemoteCaches(context)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🗑 清除远程缓存")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 导入/导出数据库
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { AppLogger.i(TAG, "点击导出数据库按钮"); viewModel.showExportDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("📤 导出数据库")
                }
                OutlinedButton(
                    onClick = { AppLogger.i(TAG, "点击导入数据库按钮"); viewModel.showImportDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("📥 导入数据库")
                }
            }

            // 导出确认对话框
            if (viewModel.showExportDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.showExportDialog = false },
                    title = { Text("导出数据库") },
                    text = { Text("将相册索引和设置导出为备份文件。\n图片文件本身不会被导出。") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.showExportDialog = false
                            exportLauncher.launch("remophoto_backup_${System.currentTimeMillis()}.zip")
                        }) {
                            Text("导出")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.showExportDialog = false }) {
                            Text("取消")
                        }
                    }
                )
            }

            // 导入确认对话框
            if (viewModel.showImportDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.showImportDialog = false },
                    title = { Text("导入数据库") },
                    text = { Text("从备份文件恢复相册索引和设置。\n⚠️ 导入将覆盖当前数据库，建议先导出备份。") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.showImportDialog = false
                            importLauncher.launch(arrayOf("application/zip", "*/*"))
                        }) {
                            Text("选择文件")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.showImportDialog = false }) {
                            Text("取消")
                        }
                    }
                )
            }

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

private fun themeModeDisplayName(mode: String): String = when (mode) {
    "light" -> "☀️ 浅色"
    "dark" -> "🌙 深色"
    else -> "📱 跟随系统"
}

private const val TAG = "Settings"

private fun darkModeTypeDisplayName(type: String): String = when (type) {
    "oled" -> "⬛ 纯黑 (OLED)"
    "lcd" -> "◼️ 深灰 (LCD)"
    else -> "🔍 自动检测"
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
