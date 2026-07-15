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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remophoto.data.local.entity.RepositoryEntity
import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.di.dependencies
import com.remophoto.ui.components.SmbDirectoryBrowserDialog
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
    val remoteConnections by viewModel.remoteConnections.collectAsState()
    val remoteSyncingRepoIds by viewModel.remoteSyncingRepoIds.collectAsState()
    val remoteSyncStatusMap by viewModel.remoteSyncStatusMap.collectAsState()
    val reauthenticatingIds by viewModel.reauthenticatingConnectionIds.collectAsState()

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
    var reauthConnectionId by remember { mutableStateOf<Long?>(null) }
    var rootConnectionId by remember { mutableStateOf<Long?>(null) }

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
                        remoteConnection = repo.remoteConnectionId?.let(remoteConnections::get),
                        isRemoteSyncing = repo.id in remoteSyncingRepoIds,
                        remoteSyncStatus = remoteSyncStatusMap[repo.id],
                        onDelete = { deleteConfirmRepoId = repo.id },
                        onRescan = { viewModel.rescanRepository(repo.id) },
                        onPause = { viewModel.pauseScan(repo.id) },
                        onCancelScan = { viewModel.cancelRepoScan(repo.id) },
                        onRemoteRefresh = { viewModel.refreshRemoteRepository(repo.id) },
                        onRemoteCancel = { viewModel.cancelRemoteSync(repo.id) },
                        onReauthenticate = { repo.remoteConnectionId?.let { reauthConnectionId = it } },
                        onChooseSmbRoot = { repo.remoteConnectionId?.let { rootConnectionId = it } },
                    )
                }
            }
        }
    }

    reauthConnectionId?.let { connectionId ->
        ReauthenticateSmbDialog(
            loading = connectionId in reauthenticatingIds,
            onDismiss = { if (connectionId !in reauthenticatingIds) reauthConnectionId = null },
            onConfirm = { password ->
                viewModel.reauthenticate(connectionId, password) { reauthConnectionId = null }
            },
        )
    }

    rootConnectionId?.let { connectionId ->
        remoteConnections[connectionId]?.let { connection ->
            SmbDirectoryBrowserDialog(
                sessionManager = context.dependencies.smbSessionManager,
                connection = connection.copy(rootPath = null),
                initialPath = connection.rootPath.orEmpty().replace('\\', '/').trim('/'),
                onSelected = { selected ->
                    viewModel.updateSmbRoot(connectionId, selected) { rootConnectionId = null }
                },
                onDismiss = { rootConnectionId = null },
            )
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
    remoteConnection: RemoteConnectionEntity? = null,
    isRemoteSyncing: Boolean = false,
    remoteSyncStatus: RepositoryManagerViewModel.ScanUiState? = null,
    onDelete: () -> Unit,
    onRescan: () -> Unit,
    onPause: () -> Unit = {},
    onCancelScan: () -> Unit = {},
    onRemoteRefresh: () -> Unit = {},
    onRemoteCancel: () -> Unit = {},
    onReauthenticate: () -> Unit = {},
    onChooseSmbRoot: () -> Unit = {},
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
                if (isScanning || isRemoteSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isRemote) {
                    when (remoteConnection?.type) {
                        RemoteType.SMB -> "SMB 共享"
                        RemoteType.HTTP_MDNS -> "remoPhoto 设备"
                        null -> "远程仓库"
                    }
                } else repo.uriString,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 统计与操作分行显示，保证窄屏下每个 48dp 操作目标都可点击。
            Column(modifier = Modifier.fillMaxWidth()) {
                // 左侧：图片数量 + 扫描时间（远程仓库显示"同步"）
                Column {
                    Text(
                        text = "${repo.imageCount} 张图片",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (isRemote) {
                            val statusText = when (remoteConnection?.status) {
                                ConnectionStatus.CONNECTED -> "已连接"
                                ConnectionStatus.DISCONNECTED -> "未连接"
                                ConnectionStatus.ERROR -> "连接异常"
                                ConnectionStatus.AUTH_REQUIRED -> "需要重新认证"
                                null -> "连接信息缺失"
                            }
                            val lastSuccess = remoteConnection?.lastConnectedTime?.let {
                                " · 上次成功 ${dateFormat.format(Date(it))}"
                            }.orEmpty()
                            statusText + lastSuccess
                        } else if (repo.lastScanTime > 0) {
                            "最后扫描: ${dateFormat.format(Date(repo.lastScanTime))}"
                        } else {
                            "未扫描"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (isRemote) {
                        if (remoteConnection?.type == RemoteType.SMB) {
                            IconButton(onClick = onChooseSmbRoot) {
                                Icon(Icons.Default.Folder, contentDescription = "选择 ${repo.name} 的相册目录")
                            }
                            IconButton(onClick = onReauthenticate) {
                                Icon(Icons.Default.Key, contentDescription = "重新认证 ${repo.name}")
                            }
                        }
                        IconButton(
                            onClick = if (isRemoteSyncing) onRemoteCancel else onRemoteRefresh,
                            enabled = remoteConnection?.status != ConnectionStatus.AUTH_REQUIRED || isRemoteSyncing,
                        ) {
                            Icon(
                                if (isRemoteSyncing) Icons.Default.Stop else Icons.Default.Refresh,
                                contentDescription = if (isRemoteSyncing) "取消刷新 ${repo.name}" else "刷新 ${repo.name}",
                            )
                        }
                    } else {
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

            if (isRemoteSyncing) {
                Spacer(modifier = Modifier.height(8.dp))
                val total = remoteSyncStatus?.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (remoteSyncStatus.indexed.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = remoteSyncStatus?.let { status ->
                        if (status.total == null) {
                            "${status.phase} · 已检查 ${status.directories} 个目录 · 已发现 ${status.discovered} 张"
                        } else {
                            "${status.phase} · ${status.indexed} / ${status.total} 张"
                        }
                    } ?: "准备刷新…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReauthenticateSmbDialog(
    loading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passwordValue by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新认证 SMB 共享") },
        text = {
            Column {
                Text("新凭据会先通过连接测试，成功后才替换本机加密凭据。")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = passwordValue,
                    onValueChange = { passwordValue = it },
                    label = { Text("密码") },
                    singleLine = true,
                    enabled = !loading,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().semantics { password() },
                )
                if (loading) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = passwordValue.isNotEmpty() && !loading,
                onClick = { onConfirm(passwordValue) },
            ) { Text("测试并更新") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text("取消") } },
    )
}
