package com.remophoto.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.local.entity.RepositoryEntity
import com.remophoto.data.remote.RemoteHttpClient
import com.remophoto.data.server.DiscoveredDevice
import com.remophoto.data.server.MdnsDiscoveryService
import com.remophoto.data.server.WifiLockManager
import com.remophoto.di.dependencies
import com.remophoto.util.AppLogger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 添加远程仓库对话框
 *
 * 双 Tab 界面：
 * - "发现设备" — mDNS 自动扫描局域网 remoPhoto 设备
 * - "手动添加" — 手动输入 IP:端口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRemoteRepoDialog(
    onDismiss: () -> Unit,
    onRepoAdded: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deps = context.dependencies

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("发现设备", "手动添加")

    // mDNS 发现
    val discovery = remember { MdnsDiscoveryService(context, WifiLockManager(context)) }
    val devices by discovery.devices.collectAsState()
    val isScanning by discovery.isScanning.collectAsState()

    // 手动输入
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("8080") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }

    // 连接中
    var connecting by remember { mutableStateOf(false) }

    // 启动/停止 mDNS 发现
    LaunchedEffect(Unit) {
        AppLogger.i("AddRemoteRepo", "对话框已打开，启动 mDNS 发现...")
        try {
            val ok = discovery.start()
            AppLogger.i("AddRemoteRepo", "mDNS 发现启动结果: $ok, isScanning=$isScanning")
            awaitCancellation()
        } finally {
            // rememberCoroutineScope 会随 Composable 一起取消，不能用于 onDispose 中的挂起清理。
            withContext(NonCancellable) {
                AppLogger.i("AddRemoteRepo", "对话框关闭，可靠停止 mDNS 发现")
                discovery.stop()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!connecting) onDismiss() },
        title = { Text("添加远程仓库") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Tab 栏
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                when (selectedTab) {
                    0 -> {
                        // ===== 发现设备 =====
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isScanning && devices.isEmpty()) {
                                    "正在扫描..."
                                } else {
                                    "可用设备: ${devices.size}"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(onClick = {
                                discovery.refresh()
                            }) {
                                Icon(Icons.Default.Refresh, "刷新")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (devices.isEmpty() && !isScanning) {
                            Text(
                                "未发现局域网内的 remoPhoto 设备\n请确保两台设备连接同一 WiFi",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        LazyColumn(
                            modifier = Modifier.heightIn(max = 200.dp)
                        ) {
                            items(devices, key = { "${it.host}:${it.port}" }) { device ->
                                DeviceItem(
                                    device = device,
                                    onConnect = {
                                        scope.launch {
                                            connecting = true
                                            addRemoteRepo(
                                                context = context,
                                                host = device.host,
                                                port = device.port,
                                                displayName = device.displayName,
                                                onSuccess = {
                                                    connecting = false
                                                    onRepoAdded()
                                                    onDismiss()
                                                },
                                                onError = { msg ->
                                                    connecting = false
                                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }
                                    },
                                    enabled = !connecting
                                )
                            }
                        }
                    }

                    1 -> {
                        // ===== 手动添加 =====
                        OutlinedTextField(
                            value = manualHost,
                            onValueChange = { manualHost = it; testResult = null },
                            label = { Text("IP 地址") },
                            placeholder = { Text("例如 192.168.1.5") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = manualPort,
                            onValueChange = { manualPort = it; testResult = null },
                            label = { Text("端口") },
                            placeholder = { Text("8080") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 测试连接按钮
                            OutlinedButton(
                                onClick = {
                                    val host = manualHost.trim()
                                    val port = manualPort.trim().toIntOrNull() ?: return@OutlinedButton
                                    scope.launch {
                                        testing = true
                                        testResult = null
                                        AppLogger.i("AddRemoteRepo", "手动测试连接开始")
                                        testResult = try {
                                            val r = RemoteHttpClient().ping(host, port)
                                            AppLogger.i("AddRemoteRepo", "手动测试结果: $r")
                                            r
                                        } catch (ex: Exception) {
                                            AppLogger.e(
                                                "AddRemoteRepo",
                                                "手动测试异常: category=${ex.javaClass.simpleName}",
                                            )
                                            false
                                        }
                                        testing = false
                                    }
                                },
                                enabled = manualHost.isNotBlank() && !testing && !connecting
                            ) {
                                Text(if (testing) "测试中..." else "测试连接")
                            }

                            // 添加按钮
                            Button(
                                onClick = {
                                    val host = manualHost.trim()
                                    val port = manualPort.trim().toIntOrNull() ?: return@Button
                                    scope.launch {
                                        connecting = true
                                        addRemoteRepo(
                                            context = context,
                                            host = host,
                                            port = port,
                                            displayName = host,
                                            onSuccess = {
                                                connecting = false
                                                onRepoAdded()
                                                onDismiss()
                                            },
                                            onError = { msg ->
                                                connecting = false
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                },
                                enabled = testResult == true && !connecting
                            ) {
                                Text("添加")
                            }
                        }

                        // 测试结果
                        testResult?.let { ok ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (ok) "✅ 连接成功" else "❌ 连接失败",
                                color = if (ok) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (connecting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !connecting) {
                Text("取消")
            }
        }
    )
}

/**
 * 发现的设备条目
 */
@Composable
private fun DeviceItem(
    device: DiscoveredDevice,
    onConnect: () -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = enabled) { onConnect() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "${device.host}:${device.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onConnect, enabled = enabled) {
                Text("连接")
            }
        }
    }
}

/**
 * 添加远程仓库到数据库
 */
private suspend fun addRemoteRepo(
    context: android.content.Context,
    host: String,
    port: Int,
    displayName: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    try {
        AppLogger.d("AddRemoteRepo", "添加远程仓库请求")
        AppLogger.i("AddRemoteRepo", "开始添加远程仓库")
        val deps = context.dependencies
        val connDao = deps.remoteConnectionDao
        val repoDao = deps.repositoryDao

        // 防止添加本机自身
        val wlm = com.remophoto.data.server.WifiLockManager(context)
        val myIp = wlm.getLanIp()
        if (myIp != null && host == myIp) {
            AppLogger.w("AddRemoteRepo", "拒绝添加本机")
            onError("无法添加本设备自身，请在其他设备上连接此设备")
            return
        }

        // 检查连接是否已存在
        val existingConn = connDao.getConnectionByHostAndPort(host, port)
        if (existingConn != null) {
            // 连接已存在 → 检查是否有对应的 RepositoryEntity
            val existingRepos = repoDao.getAllRepositoriesList()
            val hasRepo = existingRepos.any { it.remoteConnectionId == existingConn.id }
            if (hasRepo) {
                AppLogger.w("AddRemoteRepo", "设备和仓库均已存在: connId=${existingConn.id}")
                onError("该设备已添加")
                return
            }
            // 脏数据修复：连接存在但仓库缺失 → 补建仓库
            AppLogger.w("AddRemoteRepo", "连接存在但仓库缺失，补建: connId=${existingConn.id}")
            val repo = RepositoryEntity(
                uriString = "http://$host:$port",
                path = null,
                name = displayName,
                remoteConnectionId = existingConn.id,
                addedTime = System.currentTimeMillis()
            )
            repoDao.insert(repo)
            AppLogger.i("AddRemoteRepo", "✅ 仓库已补建: connId=${existingConn.id}")
            onSuccess()
            return
        }

        // 全新设备：创建连接
        AppLogger.d("AddRemoteRepo", "创建 RemoteConnectionEntity...")
        val connection = RemoteConnectionEntity(
            type = RemoteType.HTTP_MDNS,
            host = host,
            port = port,
            displayName = displayName,
            addedTime = System.currentTimeMillis(),
            status = ConnectionStatus.CONNECTED
        )
        val connId = connDao.insert(connection)
        AppLogger.i("AddRemoteRepo", "RemoteConnection 已插入: id=$connId")

        // 创建对应的仓库实体（如果失败则回滚连接）
        AppLogger.d("AddRemoteRepo", "创建 RepositoryEntity...")
        val repo = RepositoryEntity(
            uriString = "http://$host:$port",
            path = null,
            name = displayName,
            remoteConnectionId = connId,
            addedTime = System.currentTimeMillis()
        )
        try {
            repoDao.insert(repo)
            AppLogger.i("AddRemoteRepo", "RepositoryEntity 已插入")
        } catch (e: Exception) {
            // 回滚：删除已创建的连接记录
            AppLogger.e(
                "AddRemoteRepo",
                "仓库插入失败，回滚连接记录: category=${e.javaClass.simpleName}",
            )
            try { connDao.deleteById(connId) } catch (_: Exception) {}
            throw e
        }

        AppLogger.i("AddRemoteRepo", "远程仓库已添加: connId=$connId")
        onSuccess()
    } catch (e: Exception) {
        AppLogger.e("AddRemoteRepo", "添加远程仓库失败: category=${e.javaClass.simpleName}")
        onError("添加失败，请检查连接配置后重试")
    }
}
