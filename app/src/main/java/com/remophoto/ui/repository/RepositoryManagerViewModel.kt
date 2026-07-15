package com.remophoto.ui.repository

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remophoto.RemoPhotoApp
import com.remophoto.data.local.entity.RepositoryEntity
import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.remote.RemoteDataException
import com.remophoto.data.remote.RemoteErrorCategory
import com.remophoto.data.remote.RemoteConnectionIdentity
import com.remophoto.data.remote.smb.SmbPathCodec
import com.remophoto.data.remote.smb.SmbRepositorySyncWorker
import com.remophoto.data.repository.RepositoryManager
import com.remophoto.domain.usecase.ScanImagesUseCase
import com.remophoto.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.remophoto.data.scanner.RepositoryScanWorker
import com.remophoto.ui.components.SmbFormPolicy

/**
 * 仓库管理 ViewModel
 *
 * 管理仓库列表的增删改查、权限验证和扫描触发。
 */
class RepositoryManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RemoPhotoApp
    private val container = app.dependencyContainer

    /** 通过依赖容器获取扫描用例 */
    private val scanImagesUseCase: ScanImagesUseCase = container.scanImagesUseCase

    /** RepositoryManager 需要通过工厂方法获取（需 PermissionHelper — Activity 绑定） */
    var repositoryManager: RepositoryManager? = null

    private val _repositories = MutableStateFlow<List<RepositoryEntity>>(emptyList())
    val repositories: StateFlow<List<RepositoryEntity>> = _repositories.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _remoteConnections = MutableStateFlow<Map<Long, RemoteConnectionEntity>>(emptyMap())
    val remoteConnections: StateFlow<Map<Long, RemoteConnectionEntity>> = _remoteConnections.asStateFlow()

    private val _remoteSyncingRepoIds = MutableStateFlow<Set<Long>>(emptySet())
    val remoteSyncingRepoIds: StateFlow<Set<Long>> = _remoteSyncingRepoIds.asStateFlow()
    private val httpSyncJobs = ConcurrentHashMap<Long, Job>()
    private var smbSyncingRepoIds: Set<Long> = emptySet()

    private val _remoteSyncStatusMap = MutableStateFlow<Map<Long, ScanUiState>>(emptyMap())
    val remoteSyncStatusMap: StateFlow<Map<Long, ScanUiState>> = _remoteSyncStatusMap.asStateFlow()

    private val _reauthenticatingConnectionIds = MutableStateFlow<Set<Long>>(emptySet())
    val reauthenticatingConnectionIds: StateFlow<Set<Long>> = _reauthenticatingConnectionIds.asStateFlow()

    // ===== 扫描状态 =====

    /** 正在扫描的仓库 ID 集合（支持多仓库并发） */
    private val _scanningRepoIds = MutableStateFlow<Set<Long>>(emptySet())
    val scanningRepoIds: StateFlow<Set<Long>> = _scanningRepoIds.asStateFlow()

    /** 各仓库扫描进度 (repoId → progress 0..1) */
    private val _scanProgressMap = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val scanProgressMap: StateFlow<Map<Long, Float>> = _scanProgressMap.asStateFlow()

    /** 已暂停的仓库 ID 集合（UI 用：区分运行中 vs 暂停中） */
    private val _pausedRepoIds = MutableStateFlow<Set<Long>>(emptySet())
    val pausedRepoIds: StateFlow<Set<Long>> = _pausedRepoIds.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanMessage = MutableStateFlow("正在扫描…")
    val scanMessage: StateFlow<String> = _scanMessage.asStateFlow()

    data class ScanUiState(
        val phase: String = "准备扫描",
        val discovered: Int = 0,
        val indexed: Int = 0,
        val total: Int? = null,
        val remaining: Int? = null,
        val directories: Int = 0
    )

    private val _scanStatusMap = MutableStateFlow<Map<Long, ScanUiState>>(emptyMap())
    val scanStatusMap: StateFlow<Map<Long, ScanUiState>> = _scanStatusMap.asStateFlow()
    private val workManager = WorkManager.getInstance(app)

    /** 各仓库扫描 Job（线程安全） */
    private val scanJobs = ConcurrentHashMap<Long, Job>()

    /** 暂停时保存的进度 (repoId → progress)，恢复时从此位置继续 */
    private val savedProgress = ConcurrentHashMap<Long, Float>()

    /** 并发扫描信号量，最多 3 个仓库同时扫描 */
    private val scanSemaphore = Semaphore(3)

    init {
        viewModelScope.launch {
            container.remoteConnectionDao.getAllConnections().collect { connections ->
                _remoteConnections.value = connections.associateBy { it.id }
            }
        }
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(SmbRepositorySyncWorker.WORK_TAG).collect { works ->
                val activeIds = mutableSetOf<Long>()
                val states = mutableMapOf<Long, ScanUiState>()
                works.forEach { work ->
                    val repoId = work.tags.firstOrNull { it.startsWith("smb_repository_sync_repo_") }
                        ?.substringAfterLast('_')?.toLongOrNull() ?: return@forEach
                    if (work.state == WorkInfo.State.RUNNING || work.state == WorkInfo.State.ENQUEUED) {
                        activeIds += repoId
                    }
                    val data = work.progress
                    val total = data.getInt(SmbRepositorySyncWorker.KEY_TOTAL, -1).takeIf { it >= 0 }
                    states[repoId] = ScanUiState(
                        phase = data.getString(SmbRepositorySyncWorker.KEY_PHASE)
                            ?: if (work.state == WorkInfo.State.ENQUEUED) "等待网络" else "准备刷新",
                        discovered = data.getInt(SmbRepositorySyncWorker.KEY_DISCOVERED, 0),
                        indexed = data.getInt(SmbRepositorySyncWorker.KEY_INDEXED, 0),
                        total = total,
                        remaining = total?.let { (it - data.getInt(SmbRepositorySyncWorker.KEY_INDEXED, 0)).coerceAtLeast(0) },
                        directories = data.getInt(SmbRepositorySyncWorker.KEY_DIRECTORIES, 0),
                    )
                    if (work.state == WorkInfo.State.FAILED) {
                        val category = work.outputData.getString(SmbRepositorySyncWorker.KEY_ERROR)
                            ?.let { value -> runCatching { RemoteErrorCategory.valueOf(value) }.getOrNull() }
                            ?: RemoteErrorCategory.UNKNOWN
                        _errorMessage.value = SmbFormPolicy.actionableMessage(category) + "；旧索引仍保留"
                    }
                }
                smbSyncingRepoIds = activeIds
                publishRemoteSyncingRepoIds()
                _remoteSyncStatusMap.value = states
            }
        }
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(RepositoryScanWorker.WORK_TAG).collect { works ->
                val activeIds = mutableSetOf<Long>()
                val states = mutableMapOf<Long, ScanUiState>()
                works.forEach { work ->
                    val repoId = work.tags.firstOrNull { it.startsWith("repository_scan_repo_") }
                        ?.substringAfterLast('_')?.toLongOrNull() ?: return@forEach
                    if (work.state == WorkInfo.State.RUNNING || work.state == WorkInfo.State.ENQUEUED) {
                        activeIds += repoId
                    }
                    val data = work.progress
                    val total = data.getInt(RepositoryScanWorker.KEY_TOTAL, -1).takeIf { it >= 0 }
                    states[repoId] = ScanUiState(
                        phase = data.getString(RepositoryScanWorker.KEY_PHASE) ?: if (work.state == WorkInfo.State.ENQUEUED) "等待扫描" else "准备扫描",
                        discovered = data.getInt(RepositoryScanWorker.KEY_DISCOVERED, 0),
                        indexed = data.getInt(RepositoryScanWorker.KEY_INDEXED, 0),
                        total = total,
                        remaining = data.getInt(RepositoryScanWorker.KEY_REMAINING, -1).takeIf { it >= 0 },
                        directories = data.getInt(RepositoryScanWorker.KEY_DIRECTORIES, 0)
                    )
                    if (work.state == WorkInfo.State.FAILED) {
                        _errorMessage.value = "扫描失败: ${work.outputData.getString(RepositoryScanWorker.KEY_ERROR) ?: "未知错误"}；旧索引仍保留"
                    }
                }
                _scanningRepoIds.value = activeIds
                _scanStatusMap.value = states
                _scanProgressMap.value = states.mapValues { (_, state) ->
                    val total = state.total
                    if (total != null && total > 0) state.indexed.toFloat() / total else 0f
                }
                _isScanning.value = activeIds.isNotEmpty()
            }
        }
    }

    /**
     * 初始化：加载仓库列表
     */
    fun initialize(manager: RepositoryManager) {
        if (repositoryManager != null) return
        repositoryManager = manager
        viewModelScope.launch {
            manager.getAllRepositories().collect { list ->
                _repositories.value = list
                _isLoading.value = false
                AppLogger.i(TAG, "仓库管理列表加载完成: count=${list.size}")
            }
        }
    }

    /**
     * 添加仓库
     *
     * @param uri SAF 选择器返回的目录 URI
     * @param name 仓库名称
     */
    fun addRepository(uri: Uri, name: String) {
        val manager = repositoryManager ?: return
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null
                val repoId = manager.addRepository(uri, name)
                // 添加成功后自动触发扫描
                startScan(repoId)
            } catch (e: Exception) {
                _errorMessage.value = when {
                    e is IllegalStateException -> e.message  // 去重提示
                    else -> "添加仓库失败: ${e.message}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 删除仓库（同时清理远程连接和凭据）
     */
    fun deleteRepository(repoId: Long) {
        val manager = repositoryManager ?: return
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                // Phase 4: 删除前检查是否为远程仓库，清理关联数据
                val repo = manager.getRepositoryById(repoId)
                val connId = repo?.remoteConnectionId
                if (connId != null) {
                    AppLogger.i(TAG, "删除远程仓库: repoId=$repoId, connId=$connId")
                    val result = container.remoteRepositoryLifecycleService.remove(repoId)
                    if (result.externalCleanupPending) {
                        AppLogger.w(TAG, "远程仓库外部清理待重试: connId=$connId")
                    }
                } else {
                    manager.deleteRepository(repoId)
                }
                AppLogger.i(TAG, "仓库已删除: repoId=$repoId, isRemote=${connId != null}")
            } catch (e: Exception) {
                _errorMessage.value = "删除仓库失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 触发重新扫描
     */
    fun rescanRepository(repoId: Long) {
        startScan(repoId)
    }

    fun refreshRemoteRepository(repoId: Long) {
        if (repoId in _remoteSyncingRepoIds.value) return
        viewModelScope.launch {
            try {
                val repo = container.repositoryDao.getRepositoryById(repoId)
                val connection = repo?.remoteConnectionId?.let {
                    container.remoteConnectionDao.getConnectionById(it)
                }
                if (repo == null || connection == null) {
                    _errorMessage.value = "远程仓库不存在"
                    return@launch
                }
                if (connection.status == ConnectionStatus.AUTH_REQUIRED) {
                    _errorMessage.value = "请先重新认证此 SMB 仓库"
                    return@launch
                }
                if (connection.type == RemoteType.SMB) {
                    SmbRepositorySyncWorker.enqueue(app, repoId)
                    AppLogger.i(TAG, "SMB 手动刷新已提交: connectionId=${connection.id}, repoId=$repoId")
                } else {
                    startHttpSync(connection, repoId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _errorMessage.value = "远程刷新失败，请稍后重试；旧索引仍保留"
                AppLogger.e(TAG, "远程刷新提交失败: repoId=$repoId, category=${error.javaClass.simpleName}")
            }
        }
    }

    fun cancelRemoteSync(repoId: Long) {
        SmbRepositorySyncWorker.cancel(app, repoId)
        val httpJob = httpSyncJobs.remove(repoId)
        httpJob?.cancel()
        publishRemoteSyncingRepoIds()
        AppLogger.i(TAG, "远程刷新取消请求: repoId=$repoId, httpActive=${httpJob != null}")
    }

    private fun startHttpSync(connection: RemoteConnectionEntity, repoId: Long) {
        lateinit var syncJob: Job
        syncJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                container.syncRemoteRepositoryUseCase.syncAlbums(connection, repoId)
                AppLogger.i(TAG, "HTTP 手动刷新完成: connectionId=${connection.id}, repoId=$repoId")
            } catch (cancelled: CancellationException) {
                AppLogger.i(TAG, "HTTP 手动刷新取消: connectionId=${connection.id}, repoId=$repoId")
                throw cancelled
            } catch (error: Throwable) {
                _errorMessage.value = "远程刷新失败，请检查设备是否在线；旧索引仍保留"
                AppLogger.e(
                    TAG,
                    "HTTP 手动刷新失败: connectionId=${connection.id}, category=${error.javaClass.simpleName}",
                )
            } finally {
                httpSyncJobs.remove(repoId, syncJob)
                publishRemoteSyncingRepoIds()
            }
        }
        if (httpSyncJobs.putIfAbsent(repoId, syncJob) == null) {
            publishRemoteSyncingRepoIds()
            syncJob.start()
        } else {
            syncJob.cancel()
            AppLogger.i(TAG, "HTTP 手动刷新请求已忽略: repoId=$repoId, reason=already-running")
        }
    }

    private fun publishRemoteSyncingRepoIds() {
        _remoteSyncingRepoIds.value = smbSyncingRepoIds + httpSyncJobs.keys
    }

    fun reauthenticate(
        connectionId: Long,
        password: String,
        onSuccess: () -> Unit,
    ) {
        if (password.isEmpty() || connectionId in _reauthenticatingConnectionIds.value) return
        viewModelScope.launch {
            _reauthenticatingConnectionIds.update { it + connectionId }
            try {
                val connection = checkNotNull(container.remoteConnectionDao.getConnectionById(connectionId))
                container.smbSessionManager.testConnection(connection, password.toCharArray())
                container.remoteRepositoryLifecycleService.reauthenticate(connectionId, password.toCharArray())
                AppLogger.i(TAG, "SMB 重新认证完成: connectionId=$connectionId")
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val category = (error as? RemoteDataException)?.category ?: RemoteErrorCategory.UNKNOWN
                _errorMessage.value = SmbFormPolicy.actionableMessage(category)
                AppLogger.e(TAG, "SMB 重新认证失败: connectionId=$connectionId, category=$category")
            } finally {
                _reauthenticatingConnectionIds.update { it - connectionId }
            }
        }
    }

    fun updateSmbRoot(
        connectionId: Long,
        rootPath: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val connection = checkNotNull(container.remoteConnectionDao.getConnectionById(connectionId))
                require(connection.type == RemoteType.SMB)
                val normalizedRoot = SmbPathCodec.normalizeRelative(rootPath).ifBlank { null }
                val repositoryId = _repositories.value.firstOrNull {
                    it.remoteConnectionId == connectionId
                }?.id ?: error("远程仓库不存在")
                val identity = RemoteConnectionIdentity.create(
                    connection.type,
                    connection.host,
                    connection.port,
                    connection.shareName,
                    normalizedRoot,
                    connection.domain,
                    connection.username,
                )
                container.smbSessionManager.invalidate(connectionId)
                container.remoteConnectionDao.update(
                    connection.copy(
                        rootPath = normalizedRoot,
                        identityKey = identity,
                        status = ConnectionStatus.DISCONNECTED,
                    )
                )
                SmbRepositorySyncWorker.enqueue(app, repositoryId)
                AppLogger.i(
                    TAG,
                    "SMB 相册根目录已更新并提交刷新: connectionId=$connectionId, repoId=$repositoryId, " +
                        "depth=${normalizedRoot?.count { it == '/' }?.plus(1) ?: 0}",
                )
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _errorMessage.value = when {
                    error is android.database.sqlite.SQLiteConstraintException ->
                        "该扫描目录已作为另一个仓库添加"
                    error is RemoteDataException -> SmbFormPolicy.actionableMessage(error.category)
                    else -> "更新相册目录失败，请稍后重试"
                }
                AppLogger.e(TAG, "SMB 相册根目录更新失败: connectionId=$connectionId, cause=${error.javaClass.simpleName}")
            }
        }
    }

    /**
     * 暂停扫描 — 保存当前进度，取消协程，进度条保留显示
     */
    fun pauseScan(repoId: Long) {
        if (repoId in _pausedRepoIds.value) {
            AppLogger.w(TAG, "[pause] 已暂停忽略: repo=$repoId")
            return
        }
        val currentProgress = _scanProgressMap.value[repoId] ?: 0f
        savedProgress[repoId] = currentProgress
        _pausedRepoIds.update { it + repoId }
        val hadJob = scanJobs.containsKey(repoId)
        workManager.cancelUniqueWork(RepositoryScanWorker.uniqueName(repoId))
        scanJobs[repoId]?.cancel()
        scanJobs.remove(repoId)
        AppLogger.i(TAG, "[pause] 暂停: repo=$repoId progress=$currentProgress hadJob=$hadJob")
    }

    /**
     * 取消扫描 — 清除进度，下次从 0 开始
     */
    fun cancelRepoScan(repoId: Long) {
        AppLogger.i(TAG, "[cancel] 取消: repo=$repoId hasJob=${scanJobs.containsKey(repoId)} paused=${repoId in _pausedRepoIds.value}")
        savedProgress.remove(repoId)
        _pausedRepoIds.update { it - repoId }
        scanJobs[repoId]?.cancel()
        workManager.cancelUniqueWork(RepositoryScanWorker.uniqueName(repoId))
        scanJobs.remove(repoId)
        _scanningRepoIds.update { it - repoId }
        _scanProgressMap.update { it - repoId }
        _isScanning.update { _scanningRepoIds.value.isNotEmpty() }
    }

    /**
     * 取消所有扫描
     */
    fun cancelAllScans() {
        savedProgress.clear()
        _pausedRepoIds.update { emptySet() }
        scanJobs.values.forEach { it.cancel() }
        scanJobs.clear()
        _scanningRepoIds.update { emptySet() }
        _scanProgressMap.update { emptyMap() }
        _isScanning.value = false
    }

    /**
     * 开始/恢复扫描
     */
    fun startScan(repoId: Long) {
        val manager = repositoryManager ?: return
        val scanning = repoId in _scanningRepoIds.value
        val paused = repoId in _pausedRepoIds.value
        AppLogger.i(TAG, "[start] 请求: repo=$repoId scanning=$scanning paused=$paused")
        // 防重复（暂停中可重启）
        if (scanning && !paused) {
            AppLogger.d(TAG, "[start] 跳过: repo=$repoId 已在运行中")
            return
        }
        if (paused) {
            _pausedRepoIds.update { it - repoId }
            savedProgress.remove(repoId)
        } else {
            savedProgress.remove(repoId)
        }
        val request = OneTimeWorkRequestBuilder<RepositoryScanWorker>()
            .setInputData(workDataOf(RepositoryScanWorker.KEY_REPOSITORY_ID to repoId))
            .addTag(RepositoryScanWorker.WORK_TAG)
            .addTag(RepositoryScanWorker.repositoryTag(repoId))
            .build()
        workManager.enqueueUniqueWork(
            RepositoryScanWorker.uniqueName(repoId),
            ExistingWorkPolicy.REPLACE,
            request
        )
        AppLogger.i(TAG, "[start] 已提交前台 WorkManager: repo=$repoId work=${request.id}")
    }

    /**
     * 执行扫描，progressOffset 用于暂停恢复时从保存位置继续
     */
    private fun executeScan(repoId: Long, manager: RepositoryManager, progressOffset: Float = 0f) {
        _scanningRepoIds.update { it + repoId }
        _scanProgressMap.update { it + (repoId to progressOffset) }
        _isScanning.value = true

        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                scanSemaphore.withPermit {
                    try {
                        val repo = manager.getRepositoryById(repoId) ?: run {
                            _errorMessage.value = "仓库不存在"
                            return@withPermit
                        }

                        val rootUri = Uri.parse(repo.uriString)

                        val totalImages = scanImagesUseCase.executeFullScan(
                            repoId = repoId,
                            rootUri = rootUri,
                            onProgress = { rawProgress ->
                                if (!isActive) {
                                    AppLogger.i(TAG, "[scan] 检测取消: repo=$repoId progress=$rawProgress")
                                    throw CancellationException("Cancelled")
                                }
                                val displayProgress = progressOffset + rawProgress * (1f - progressOffset)
                                _scanProgressMap.update { it + (repoId to displayProgress) }
                            }
                        )

                        _scanProgressMap.update { it + (repoId to 1f) }
                        _scanMessage.value = "扫描完成 ($totalImages 张)"
                        AppLogger.i(TAG, "[scan] 完成: repo=$repoId images=$totalImages")

                    } catch (e: CancellationException) {
                        AppLogger.i(TAG, "[scan] 取消异常: repo=$repoId paused=${repoId in _pausedRepoIds.value}")
                    } catch (e: Exception) {
                        _errorMessage.value = "扫描失败: ${e.message}"
                        AppLogger.e(TAG, "[scan] 异常: repo=$repoId ${e.message}", e)
                    } finally {
                        if (repoId !in _pausedRepoIds.value) {
                            _scanningRepoIds.update { it - repoId }
                            _scanProgressMap.update { it - repoId }
                            AppLogger.d(TAG, "[scan] 清理(取消): repo=$repoId")
                        } else {
                            AppLogger.d(TAG, "[scan] 保留(暂停): repo=$repoId")
                        }
                        _isScanning.update { _scanningRepoIds.value.isNotEmpty() }
                        scanJobs.remove(repoId)
                    }
                }
            } catch (e: CancellationException) {
                AppLogger.i(TAG, "[scan] 信号量等中取消: repo=$repoId paused=${repoId in _pausedRepoIds.value}")
                if (repoId !in _pausedRepoIds.value) {
                    _scanningRepoIds.update { it - repoId }
                    _scanProgressMap.update { it - repoId }
                }
                _isScanning.update { _scanningRepoIds.value.isNotEmpty() }
                scanJobs.remove(repoId)
            }
        }
        scanJobs[repoId] = job
        AppLogger.i(TAG, "[scan] 提交: repo=$repoId offset=$progressOffset")
    }

    /**
     * 后台运行扫描（关闭对话框，扫描继续在后台执行）
     */
    fun runScanInBackground() {
        // 仅清除 UI 状态，扫描协程仍在 viewModelScope 中运行
        _isScanning.value = false
    }

    /**
     * 清除错误信息
     */
    fun clearError() {
        _errorMessage.value = null
    }

    companion object {
        private const val TAG = "RepoManagerVM"
    }
}
