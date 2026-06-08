package com.remophoto.ui.repository

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remophoto.RemoPhotoApp
import com.remophoto.data.local.entity.RepositoryEntity
import com.remophoto.data.repository.RepositoryManager
import com.remophoto.domain.usecase.ScanImagesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    /** 当前正在扫描的仓库 ID（null = 无扫描进行中） */
    private val _scanningRepoId = MutableStateFlow<Long?>(null)
    val scanningRepoId: StateFlow<Long?> = _scanningRepoId.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress: StateFlow<Float> = _scanProgress.asStateFlow()

    private val _scanMessage = MutableStateFlow("正在扫描…")
    val scanMessage: StateFlow<String> = _scanMessage.asStateFlow()

    private val _scanImageCount = MutableStateFlow(0)
    val scanImageCount: StateFlow<Int> = _scanImageCount.asStateFlow()

    /** 当前扫描协程的 Job，用于取消 */
    private var scanJob: Job? = null

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
     * 删除仓库
     */
    fun deleteRepository(repoId: Long) {
        val manager = repositoryManager ?: return
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null
                manager.deleteRepository(repoId)
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
     * 取消当前扫描
     */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
        _scanningRepoId.value = null
        _scanMessage.value = "扫描已取消"
    }

    /**
     * 执行全量扫描
     */
    fun startScan(repoId: Long) {
        val manager = repositoryManager ?: return
        // 如果已有扫描在进行中，先取消
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _isScanning.value = true
                _scanningRepoId.value = repoId
                _scanProgress.value = 0f
                _scanMessage.value = "正在准备扫描…"
                _scanImageCount.value = 0

                val repo = manager.getRepositoryById(repoId) ?: run {
                    _errorMessage.value = "仓库不存在"
                    _isScanning.value = false
                    return@launch
                }

                val rootUri = Uri.parse(repo.uriString)

                val totalImages = scanImagesUseCase.executeFullScan(
                    repoId = repoId,
                    rootUri = rootUri,
                    onProgress = { progress ->
                        _scanProgress.value = progress
                        _scanMessage.value = when {
                            progress < 0.5f -> "正在遍历目录…"
                            progress < 0.6f -> "正在创建相册…"
                            progress < 0.9f -> "正在提取图片索引…"
                            progress < 1f -> "正在更新统计…"
                            else -> "扫描完成"
                        }
                    }
                )

                _scanImageCount.value = totalImages
                _scanProgress.value = 1f
                _scanMessage.value = "扫描完成，共发现 $totalImages 张图片"

                kotlinx.coroutines.delay(500)

                _isScanning.value = false
                _scanningRepoId.value = null
                scanJob = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                _isScanning.value = false
                _scanningRepoId.value = null
                scanJob = null
                // 取消是预期行为，不需要显示错误
            } catch (e: Exception) {
                _errorMessage.value = "扫描失败: ${e.message}"
                _isScanning.value = false
                _scanningRepoId.value = null
                scanJob = null
            }
        }
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
}
