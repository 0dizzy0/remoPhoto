package com.remophoto.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.remote.RemoteDataException
import com.remophoto.data.remote.RemoteErrorCategory
import com.remophoto.data.remote.smb.SmbRepositorySyncWorker
import com.remophoto.data.remote.smb.DiscoveredSmbServer
import com.remophoto.data.remote.smb.SmbDirectoryEntry
import com.remophoto.data.remote.smb.SmbLanDiscovery
import com.remophoto.data.remote.smb.SmbPathCodec
import com.remophoto.data.remote.smb.SmbSessionManager
import com.remophoto.data.repository.RemoteRepositoryConfig
import com.remophoto.di.dependencies
import com.remophoto.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun AddRemoteRepoDialog(
    onDismiss: () -> Unit,
    onRepoAdded: () -> Unit,
) {
    var protocol by rememberSaveable { mutableStateOf<RemoteType?>(null) }
    when (protocol) {
        RemoteType.HTTP_MDNS -> RemoPhotoRepoDialog(
            onDismiss = onDismiss,
            onRepoAdded = onRepoAdded,
        )
        RemoteType.SMB -> AddSmbRepositoryDialog(
            onDismiss = onDismiss,
            onRepoAdded = onRepoAdded,
        )
        null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("添加远程仓库") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("选择连接协议", style = MaterialTheme.typography.bodyMedium)
                    ProtocolChoice(
                        title = "remoPhoto 设备",
                        description = "发现或手动连接同一局域网内的 remoPhoto",
                        onClick = { protocol = RemoteType.HTTP_MDNS },
                    )
                    ProtocolChoice(
                        title = "SMB 共享",
                        description = "连接 Windows、macOS、Linux 或 NAS 的 SMB2/3 共享",
                        onClick = { protocol = RemoteType.SMB },
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }
}

@Composable
private fun ProtocolChoice(title: String, description: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = false, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = false, onClick = onClick)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddSmbRepositoryDialog(
    onDismiss: () -> Unit,
    onRepoAdded: () -> Unit,
) {
    val context = LocalContext.current
    val deps = context.dependencies
    val scope = rememberCoroutineScope()
    val passwordFocus = remember { FocusRequester() }
    var displayName by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("445") }
    var shareName by rememberSaveable { mutableStateOf("") }
    var rootPath by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var domain by rememberSaveable { mutableStateOf("") }
    // 密码不进入 SavedState；旋转或进程恢复后要求重新输入并重新测试。
    var passwordValue by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var errors by remember { mutableStateOf(SmbFormErrors()) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var showDiscovery by remember { mutableStateOf(false) }
    var showDirectoryBrowser by remember { mutableStateOf(false) }

    fun values() = SmbFormValues(
        displayName, host, port, shareName, rootPath, username, domain, passwordValue
    )
    fun invalidateTest() {
        tested = false
        resultMessage = null
    }
    fun normalizedRoot(value: String?): String? = SmbPathCodec.normalizeRelative(value).ifBlank { null }
    fun transientConnection(selectedRoot: String? = normalizedRoot(rootPath)) = RemoteConnectionEntity(
        type = RemoteType.SMB,
        host = host.trim(),
        port = port.trim().toInt(),
        displayName = displayName.trim(),
        shareName = shareName.trim(),
        username = username.trim(),
        domain = domain.trim().ifBlank { null },
        rootPath = normalizedRoot(selectedRoot),
        addedTime = 0L,
        status = ConnectionStatus.DISCONNECTED,
    )
    fun config() = RemoteRepositoryConfig(
        type = RemoteType.SMB,
        host = host.trim(),
        port = port.trim().toInt(),
        displayName = displayName.trim(),
        shareName = shareName.trim(),
        username = username.trim(),
        domain = domain.trim().ifBlank { null },
        rootPath = normalizedRoot(rootPath),
    )

    if (showDiscovery) {
        SmbLanDiscoveryDialog(
            discovery = deps.smbLanDiscovery,
            onServerSelected = { server ->
                host = server.host
                port = server.port.toString()
                if (displayName.isBlank()) displayName = server.displayName
                invalidateTest()
                showDiscovery = false
            },
            onDismiss = { showDiscovery = false },
        )
        return
    }
    if (showDirectoryBrowser) {
        SmbDirectoryBrowserDialog(
            sessionManager = deps.smbSessionManager,
            connection = transientConnection(selectedRoot = null),
            password = passwordValue,
            initialPath = rootPath.trim().trim('/', '\\'),
            onSelected = { selected ->
                rootPath = selected
                invalidateTest()
                showDirectoryBrowser = false
            },
            onDismiss = { showDirectoryBrowser = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!testing && !saving) onDismiss() },
        title = { Text("添加 SMB 共享") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { showDiscovery = true },
                    enabled = !testing && !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Text(" 扫描局域网 SMB 设备")
                }
                Text(
                    "也可以继续手动填写服务器信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SmbTextField(displayName, { displayName = it; invalidateTest() }, "显示名称", errors.displayName)
                SmbTextField(host, { host = it; invalidateTest() }, "主机名或 IP 地址", errors.host)
                SmbTextField(
                    port, { port = it.filter(Char::isDigit); invalidateTest() }, "端口", errors.port,
                    keyboardType = KeyboardType.Number,
                )
                SmbTextField(shareName, { shareName = it; invalidateTest() }, "共享名", errors.shareName)
                SmbTextField(
                    rootPath,
                    { rootPath = it; invalidateTest() },
                    "相册根目录（可手动填写）",
                    errors.rootPath,
                )
                SmbTextField(username, { username = it; invalidateTest() }, "用户名", errors.username)
                SmbTextField(
                    domain, { domain = it; invalidateTest() }, "域（可选）", null,
                    imeAction = ImeAction.Next,
                    keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                )
                OutlinedTextField(
                    value = passwordValue,
                    onValueChange = { passwordValue = it; invalidateTest() },
                    label = { Text("密码") },
                    isError = errors.password != null,
                    supportingText = errors.password?.let { message -> ({ Text(message) }) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                if (passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus).semantics { password() },
                )
                OutlinedButton(
                    enabled = !testing && !saving,
                    onClick = {
                        val validation = SmbFormPolicy.validate(values())
                        errors = validation
                        if (validation.isEmpty) showDirectoryBrowser = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Text(" 选择相册目录")
                }
                resultMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tested) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                if (testing || saving) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                        Text(if (testing) "  正在测试连接…" else "  正在安全保存…")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = tested && !testing && !saving,
                onClick = {
                    scope.launch {
                        saving = true
                        try {
                            val created = deps.remoteRepositoryLifecycleService.saveTested(
                                config(), passwordValue.toCharArray()
                            )
                            passwordValue = ""
                            SmbRepositorySyncWorker.enqueue(context, created.repositoryId)
                            AppLogger.i(
                                "AddSmbRepo",
                                "SMB 仓库添加完成并提交首次刷新: connectionId=${created.connectionId}, " +
                                    "repositoryId=${created.repositoryId}",
                            )
                            onRepoAdded()
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            resultMessage = if (error is IllegalStateException) {
                                "该 SMB 仓库已存在"
                            } else {
                                "保存失败，请稍后重试"
                            }
                            tested = false
                            AppLogger.e("AddSmbRepo", "SMB 仓库保存失败: category=${error.javaClass.simpleName}")
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    enabled = !testing && !saving,
                    onClick = {
                        val validation = SmbFormPolicy.validate(values())
                        errors = validation
                        if (!validation.isEmpty) return@OutlinedButton
                        scope.launch {
                            testing = true
                            tested = false
                            resultMessage = null
                            AppLogger.i("AddSmbRepo", "SMB 连接测试开始")
                            try {
                                val report = deps.smbSessionManager.testConnection(
                                    transientConnection(), passwordValue.toCharArray()
                                )
                                tested = true
                                resultMessage = "连接成功 · ${report.dialect} · 可访问 ${report.entryCount} 个条目"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                val category = (error as? RemoteDataException)?.category ?: RemoteErrorCategory.UNKNOWN
                                resultMessage = SmbFormPolicy.actionableMessage(category)
                                AppLogger.e("AddSmbRepo", "SMB 连接测试失败: category=$category")
                            } finally {
                                testing = false
                            }
                        }
                    },
                ) { Text("测试连接") }
                TextButton(onClick = onDismiss, enabled = !testing && !saving) { Text("取消") }
            }
        },
    )
}

@Composable
private fun SmbLanDiscoveryDialog(
    discovery: SmbLanDiscovery,
    onServerSelected: (DiscoveredSmbServer) -> Unit,
    onDismiss: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var servers by remember { mutableStateOf<List<DiscoveredSmbServer>>(emptyList()) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            servers = discovery.discover()
            loading = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = true
            loading = false
            AppLogger.e("SmbDiscoveryUI", "SMB 局域网发现失败: cause=${failure.javaClass.simpleName}")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("局域网 SMB 设备") },
        text = {
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp), strokeWidth = 2.dp)
                    Text("  正在扫描，通常需要几秒…")
                }
                error -> Text("扫描失败，请检查 Wi-Fi 后重试，或返回手动填写。")
                servers.isEmpty() -> Text("未发现 SMB 设备。部分路由器会阻止设备发现，可以返回后手动填写 IP 地址。")
                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(servers, key = { "${it.host}:${it.port}" }) { server ->
                        TextButton(
                            onClick = { onServerSelected(server) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(server.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${server.host}:${server.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("手动填写") } },
    )
}

@Composable
internal fun SmbDirectoryBrowserDialog(
    sessionManager: SmbSessionManager,
    connection: RemoteConnectionEntity,
    password: String? = null,
    initialPath: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentPath by rememberSaveable { mutableStateOf(initialPath) }
    var directories by remember { mutableStateOf<List<SmbDirectoryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentPath) {
        loading = true
        errorMessage = null
        try {
            val loaded = if (password == null) sessionManager.listDirectories(connection, currentPath)
            else sessionManager.listDirectories(connection, password.toCharArray(), currentPath)
            directories = loaded
            loading = false
            AppLogger.i(
                "SmbDirectoryUI",
                "SMB 目录浏览完成: depth=${currentPath.count { c -> c == '/'}}, children=${loaded.size}",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val category = (error as? RemoteDataException)?.category ?: RemoteErrorCategory.UNKNOWN
            errorMessage = SmbFormPolicy.actionableMessage(category)
            loading = false
            AppLogger.e("SmbDirectoryUI", "SMB 目录浏览失败: category=$category")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择相册根目录") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { currentPath = currentPath.substringBeforeLast('/', "") },
                        enabled = currentPath.isNotBlank() && !loading,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级目录") }
                    Text(
                        if (currentPath.isBlank()) "共享根目录" else currentPath,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(24.dp), strokeWidth = 2.dp)
                        Text("  正在读取目录…")
                    }
                    errorMessage != null -> Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                    directories.isEmpty() -> Text("此目录没有子目录，可以直接选择当前目录。")
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(directories, key = { it.name }) { directory ->
                            TextButton(
                                onClick = {
                                    currentPath = if (currentPath.isBlank()) directory.name else "$currentPath/${directory.name}"
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null)
                                Text(" ${directory.name}", modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSelected(currentPath) }, enabled = !loading && errorMessage == null) {
                Text("选择当前目录")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}

@Composable
private fun SmbTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { message -> ({ Text(message) }) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = keyboardActions,
        modifier = Modifier.fillMaxWidth(),
    )
}
