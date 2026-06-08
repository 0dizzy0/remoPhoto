package com.remophoto.ui.repository

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remophoto.data.local.entity.RepositoryEntity
import com.remophoto.ui.scanner.ScanProgressDialog
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
    onScanComplete: () -> Unit = {}
) {
    val context = LocalContext.current
    val repositories by viewModel.repositories.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // 扫描状态
    val isScanning by viewModel.isScanning.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val scanMessage by viewModel.scanMessage.collectAsState()
    val scanImageCount by viewModel.scanImageCount.collectAsState()
    val scanningRepoId by viewModel.scanningRepoId.collectAsState()

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

    // 错误提示
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    // 扫描完成后回调
    LaunchedEffect(isScanning, scanProgress) {
        if (!isScanning && scanProgress >= 1f) {
            onScanComplete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("仓库管理") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("← 返回")
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
                        isScanning = scanningRepoId == repo.id,
                        scanProgress = if (scanningRepoId == repo.id) scanProgress else 0f,
                        onDelete = { deleteConfirmRepoId = repo.id },
                        onRescan = { viewModel.rescanRepository(repo.id) }
                    )
                }
            }
        }
    }

    // 删除确认对话框
    deleteConfirmRepoId?.let { repoId ->
        val repo = repositories.find { it.id == repoId }
        AlertDialog(
            onDismissRequest = { deleteConfirmRepoId = null },
            title = { Text("确认删除") },
            text = {
                Text("确定要删除仓库「${repo?.name ?: ""}」吗？\n\n此操作将同时删除该仓库下的所有图片索引和相册数据。")
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

    // 扫描进度对话框
    if (isScanning) {
        ScanProgressDialog(
            progress = scanProgress,
            message = scanMessage,
            currentCount = scanImageCount,
            onDismissRequest = { /* 扫描中不可关闭 */ },
            onRunInBackground = { viewModel.runScanInBackground() },
            onCancel = { viewModel.cancelScan() }
        )
    }
}

/**
 * 单个仓库列表项
 */
@Composable
private fun RepositoryItem(
    repo: RepositoryEntity,
    isScanning: Boolean = false,
    scanProgress: Float = 0f,
    onDelete: () -> Unit,
    onRescan: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

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
                Text(
                    text = repo.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // 扫描中指示器
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }

                // 权限状态指示
                val isValid = try {
                    // 简单判断：有路径信息则假定有效（不阻塞主线程做 IO 验证）
                    !repo.uriString.isNullOrBlank()
                } catch (e: Exception) {
                    false
                }
                if (!isValid) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "权限失效",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 路径
            Text(
                text = repo.path ?: repo.uriString,
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
                // 左侧：图片数量 + 扫描时间
                Column {
                    Text(
                        text = "${repo.imageCount} 张图片",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (repo.lastScanTime > 0) {
                            "最后扫描: ${dateFormat.format(Date(repo.lastScanTime))}"
                        } else {
                            "未扫描"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 右侧：操作按钮
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onRescan) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "重新扫描",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除仓库",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // 扫描进度条（仅扫描中的仓库显示）
            if (isScanning) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { scanProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
