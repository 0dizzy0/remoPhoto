package com.remophoto.ui.repository

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remophoto.data.local.entity.RepositoryEntity
import java.text.SimpleDateFormat
import java.util.*

/**
 * 仓库管理页面
 *
 * 功能：
 * - 仓库列表（名称、路径、图片数量、最后扫描时间）
 * - FAB 添加仓库 → SAF 目录选择器
 * - 长按仓库 → 删除确认 / 重新扫描
 * - 空状态提示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepositoryManagerScreen(
    viewModel: RepositoryManagerViewModel,
    onBack: () -> Unit,
    onSettingsClick: () -> Unit = {},
    onScanComplete: () -> Unit = {}
) {
    val context = LocalContext.current
    val repositories by viewModel.repositories.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // 扫描状态
    val isScanning by viewModel.isScanning.collectAsState()
    val scanningRepoIds by viewModel.scanningRepoIds.collectAsState()
    val pausedRepoIds by viewModel.pausedRepoIds.collectAsState()
    val scanProgressMap by viewModel.scanProgressMap.collectAsState()
    val scanMessage by viewModel.scanMessage.collectAsState()
    val scanStatusMap by viewModel.scanStatusMap.collectAsState()

    // SAF 目录选择器
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.addRepository(it, "")
        }
    }

    // 删除确认对话框
    var deleteConfirmRepoId by remember { mutableStateOf<Long?>(null) }

    BackHandler {
        onBack()
    }

    // 错误提示
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    // 扫描完成 Snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(isScanning) {
        if (!isScanning && scanningRepoIds.isEmpty()) {
            onScanComplete()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("仓库管理") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Outlined.Tune, contentDescription = "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { directoryPickerLauncher.launch(null) }
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加仓库")
            }
        }
    ) { padding ->
        if (isLoading && repositories.isEmpty()) {
            // 加载中
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (repositories.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "📁",
                        style = MaterialTheme.typography.displayLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无仓库",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "点击右下角 + 添加图片文件夹",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // 仓库列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(repositories, key = { it.id }) { repo ->
                    RepositoryItem(
                        repo = repo,
                        isScanning = repo.id in scanningRepoIds,
                        isPaused = repo.id in pausedRepoIds,
                        scanProgress = scanProgressMap[repo.id] ?: 0f,
                        scanStatus = scanStatusMap[repo.id],
                        onDelete = { deleteConfirmRepoId = repo.id },
                        onRescan = { viewModel.rescanRepository(repo.id) },
                        onPause = { viewModel.pauseScan(repo.id) },
                        onCancelScan = { viewModel.cancelRepoScan(repo.id) }
                    )
                }
            }
        }
    }

    // 删除确认对话框
    deleteConfirmRepoId?.let { repoId ->
        val repo = repositories.find { it.id == repoId }
        val isRemote = repo?.remoteConnectionId != null
        AlertDialog(
            onDismissRequest = { deleteConfirmRepoId = null },
            title = { Text("确认删除") },
            text = {
                if (isRemote) {
                    Text("确定要删除远程仓库「${repo?.name ?: ""}」吗？\n\n此操作将同时删除该仓库下的所有图片索引和相册数据。远程连接凭据也将被清除。")
                } else {
                    Text("确定要删除仓库「${repo?.name ?: ""}」吗？\n\n此操作将同时删除该仓库下的所有图片索引和相册数据。\n\n⚠️ 不会删除存储中的原始图片文件。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRepository(repoId)
                        deleteConfirmRepoId = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmRepoId = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 扫描进度已通过仓库卡片内联显示，不再使用模态对话框
}

/**
 * 单个仓库列表项
 */
@Composable
private fun RepositoryItem(
    repo: RepositoryEntity,
    isScanning: Boolean = false,
    isPaused: Boolean = false,
    scanProgress: Float = 0f,
    scanStatus: RepositoryManagerViewModel.ScanUiState? = null,
    onDelete: () -> Unit,
    onRescan: () -> Unit,
    onPause: () -> Unit = {},
    onCancelScan: () -> Unit = {}
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    val isRemote = repo.remoteConnectionId != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isScanning) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isRemote) "🌐" else "📁",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = repo.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 扫描中指示器
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 路径
            Text(
                text = repo.uriString,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 统计信息行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：图片数量 + 扫描时间（远程仓库显示"同步"）
                Column {
                    Text(
                        text = "${repo.imageCount} 张图片",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (isRemote) {
                            "远程仓库 · 点击自动同步"
                        } else if (repo.lastScanTime > 0) {
                            "最后扫描: ${dateFormat.format(Date(repo.lastScanTime))}"
                        } else {
                            "未扫描"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 右侧：操作按钮
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!isRemote) {
                        // 本地仓库：▶/⏸ | ⏹ | 🗑
                        val isActive = isScanning && !isPaused
                        IconButton(onClick = {
                            when {
                                isPaused -> onRescan()
                                isScanning -> onPause()
                                else -> onRescan()
                            }
                        }) {
                            Icon(
                                imageVector = when {
                                    isPaused -> Icons.Default.PlayArrow
                                    isScanning -> Icons.Default.Pause
                                    else -> Icons.Default.PlayArrow
                                },
                                contentDescription = when {
                                    isPaused -> "恢复扫描"
                                    isScanning -> "暂停扫描"
                                    else -> "开始扫描"
                                },
                                tint = when {
                                    isPaused -> MaterialTheme.colorScheme.primary
                                    isScanning -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                        IconButton(
                            onClick = onCancelScan,
                            enabled = isScanning
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "取消扫描",
                                tint = if (isScanning) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                    // 删除按钮（始终显示）
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除仓库",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // 扫描进度条（仅本地扫描中的仓库显示）
            if (isScanning && !isRemote) {
                Spacer(modifier = Modifier.height(8.dp))
                if (scanStatus?.total != null) {
                    LinearProgressIndicator(
                        progress = { scanProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        scanStatus == null -> "准备扫描…"
                        scanStatus.total == null ->
                            "${scanStatus.phase} · 已检查 ${scanStatus.directories} 个目录 · 已发现 ${scanStatus.discovered} 张"
                        else ->
                            "${scanStatus.phase} · 已扫描 ${scanStatus.indexed} / ${scanStatus.total} 张 · 剩余 ${scanStatus.remaining ?: 0} 张"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
