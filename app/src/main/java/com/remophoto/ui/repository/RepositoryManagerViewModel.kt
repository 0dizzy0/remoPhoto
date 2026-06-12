package com.remophoto.ui.repository

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remophoto.RemoPhotoApp
import com.remophoto.data.local.entity.RepositoryEntity
import com.remophoto.data.repository.RepositoryManager
import com.remophoto.domain.usecase.ScanImagesUseCase
import com.remophoto.util.AppLogger
import kotlinx.coroutines.CancellationException
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

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

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

    /** 各仓库扫描 Job（线程安全） */
    private val scanJobs = ConcurrentHashMap<Long, Job>()

    /** 暂停时保存的进度 (repoId → progress)，恢复时从此位置继续 */
    private val savedProgress = ConcurrentHashMap<Long, Float>()

    /** 并发扫描信号量，最多 3 个仓库同时扫描 */
    private val scanSemaphore = Semaphore(3)

    /**
     * 初始化：加载仓库列表
     */
    fun initialize(manager: RepositoryManager) {
        repositoryManager = manager
        viewModelScope.launch {
            manager.getAllRepositories().collect { list ->
                _repositories.value = list
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
                    // 清理 Keystore 凭据
                    try {
                        container.keyStoreManager.deleteCredential(connId)
                        AppLogger.d(TAG, "已删除 Keystore 凭据: connId=$connId")
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "删除 Keystore 凭据失败: $e")
                    }
                    // 删除远程连接记录
                    try {
                        container.remoteConnectionDao.deleteById(connId)
                        AppLogger.d(TAG, "已删除 RemoteConnection: connId=$connId")
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "删除 RemoteConnection 失败: $e")
                    }
                }

                manager.deleteRepository(repoId)
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
        val offset = if (paused) {
            _pausedRepoIds.update { it - repoId }
            savedProgress.remove(repoId) ?: 0f
        } else {
            savedProgress.remove(repoId)
            0f
        }
        executeScan(repoId, manager, offset)
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
