package com.remophoto.ui.settings

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remophoto.RemoPhotoApp
import com.remophoto.data.repository.ImageRepository
import com.remophoto.data.repository.SettingsRepository
import com.remophoto.data.server.HttpServerForegroundService
import com.remophoto.data.server.HttpServerManager
import com.remophoto.domain.model.SortOrder
import com.remophoto.domain.model.AlbumSortOrder
import com.remophoto.util.AppLogger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 设置 ViewModel
 *
 * 管理全局设置的状态读取与写入，通过 SettingsRepository 持久化。
 * 支持的主题模式、排序、分页、自动播放间隔、音量键翻页等。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository: SettingsRepository =
        (application as RemoPhotoApp).dependencyContainer.settingsRepository
    private val imageRepository: ImageRepository =
        (application as RemoPhotoApp).dependencyContainer.imageRepository

    // ===== 状态 =====

    private val _themeMode = MutableStateFlow("system")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _defaultSortOrder = MutableStateFlow(SortOrder.DEFAULT)
    val defaultSortOrder: StateFlow<SortOrder> = _defaultSortOrder.asStateFlow()

    private val _albumSortOrder = MutableStateFlow(AlbumSortOrder.DEFAULT)
    val albumSortOrder: StateFlow<AlbumSortOrder> = _albumSortOrder.asStateFlow()

    private val _albumsPerPage = MutableStateFlow(20)
    val albumsPerPage: StateFlow<Int> = _albumsPerPage.asStateFlow()

    private val _slideshowInterval = MutableStateFlow(3)
    val slideshowInterval: StateFlow<Int> = _slideshowInterval.asStateFlow()

    private val _useVolumeKeys = MutableStateFlow(true)
    val useVolumeKeys: StateFlow<Boolean> = _useVolumeKeys.asStateFlow()

    private val _darkModeType = MutableStateFlow("auto")
    val darkModeType: StateFlow<String> = _darkModeType.asStateFlow()

    private val _highContrast = MutableStateFlow(false)
    val highContrast: StateFlow<Boolean> = _highContrast.asStateFlow()

    // 存储空间
    private val _totalImageCount = MutableStateFlow(0)
    val totalImageCount: StateFlow<Int> = _totalImageCount.asStateFlow()

    private val _totalStorageSize = MutableStateFlow(0L)
    val totalStorageSize: StateFlow<Long> = _totalStorageSize.asStateFlow()

    // Phase 4: 远程服务状态
    private val _httpServerEnabled = MutableStateFlow(false)
    val httpServerEnabled: StateFlow<Boolean> = _httpServerEnabled.asStateFlow()

    private val _httpServerPort = MutableStateFlow(8080)
    val httpServerPort: StateFlow<Int> = _httpServerPort.asStateFlow()

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()

    private val _serverAddress = MutableStateFlow<String?>(null)
    val serverAddress: StateFlow<String?> = _serverAddress.asStateFlow()

    private val _remoteThumbCacheSize = MutableStateFlow("计算中…")
    val remoteThumbCacheSize: StateFlow<String> = _remoteThumbCacheSize.asStateFlow()

    private val _remoteImageCacheSize = MutableStateFlow("计算中…")
    val remoteImageCacheSize: StateFlow<String> = _remoteImageCacheSize.asStateFlow()

    // 导入/导出对话框
    var showExportDialog by mutableStateOf(false)
    var showImportDialog by mutableStateOf(false)

    // ===== 初始化：从 DataStore 加载 =====

    init {
        viewModelScope.launch {
            settingsRepository.themeMode.collect { _themeMode.value = it }
        }
        viewModelScope.launch {
            settingsRepository.defaultSortOrder.collect { _defaultSortOrder.value = it }
        }
        viewModelScope.launch {
            settingsRepository.albumSortOrder.collect { _albumSortOrder.value = it }
        }
        viewModelScope.launch {
            settingsRepository.albumsPerPage.collect { _albumsPerPage.value = it }
        }
        viewModelScope.launch {
            settingsRepository.slideshowInterval.collect { _slideshowInterval.value = it }
        }
        viewModelScope.launch {
            settingsRepository.useVolumeKeys.collect { _useVolumeKeys.value = it }
        }
        viewModelScope.launch {
            settingsRepository.darkModeType.collect { _darkModeType.value = it }
        }
        viewModelScope.launch {
            settingsRepository.highContrast.collect { _highContrast.value = it }
        }
        viewModelScope.launch {
            settingsRepository.httpServerEnabled.collect { _httpServerEnabled.value = it }
        }
        viewModelScope.launch {
            settingsRepository.httpServerPort.collect { _httpServerPort.value = it }
        }
        viewModelScope.launch {
            settingsRepository.deviceName.collect { _deviceName.value = it }
        }

        // 服务恢复由 Application 统一负责；设置页只观察真实运行状态，避免每次进页重复启动。
        viewModelScope.launch {
            HttpServerForegroundService.runtimeRunning.collect { _serverRunning.value = it }
        }
        viewModelScope.launch {
            HttpServerForegroundService.runtimeAddress.collect { _serverAddress.value = it }
        }

        AppLogger.i(TAG, "SettingsViewModel 初始化完成")
        refreshStorageInfo()
    }

    /** 每次进入设置页时刷新，避免共享 ViewModel 长期显示初始化时的旧统计。 */
    fun refreshStorageInfo() {
        AppLogger.i(TAG, "刷新存储与远程缓存统计")
        loadStorageStats()
        loadRemoteCacheStats()
    }

    private fun loadStorageStats() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _totalImageCount.value = imageRepository.getTotalCount()
                _totalStorageSize.value = imageRepository.getTotalFileSize()
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载存储统计失败", e)
            }
        }
    }

    // ===== 写操作 =====

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
            AppLogger.i(TAG, "主题模式已更新: $mode")
        }
    }

    fun setDefaultSortOrder(order: SortOrder) {
        _defaultSortOrder.value = order
        viewModelScope.launch {
            settingsRepository.setDefaultSortOrder(order)
            AppLogger.i(TAG, "默认排序已更新: ${order.displayName}")
        }
    }

    fun setAlbumSortOrder(order: AlbumSortOrder) {
        _albumSortOrder.value = order
        viewModelScope.launch {
            settingsRepository.setAlbumSortOrder(order)
            AppLogger.i(TAG, "相册列表排序已更新: ${order.displayName}")
        }
    }

    fun setAlbumsPerPage(count: Int) {
        _albumsPerPage.value = count
        viewModelScope.launch {
            settingsRepository.setAlbumsPerPage(count)
            AppLogger.i(TAG, "每页相册数已更新: $count")
        }
    }

    fun setSlideshowInterval(seconds: Int) {
        _slideshowInterval.value = seconds
        viewModelScope.launch {
            settingsRepository.setSlideshowInterval(seconds)
            AppLogger.i(TAG, "自动播放间隔已更新: ${seconds}s")
        }
    }

    fun setUseVolumeKeys(enabled: Boolean) {
        _useVolumeKeys.value = enabled
        viewModelScope.launch {
            settingsRepository.setUseVolumeKeys(enabled)
            AppLogger.i(TAG, "音量键翻页已更新: $enabled")
        }
    }

    fun setDarkModeType(type: String) {
        _darkModeType.value = type
        viewModelScope.launch {
            settingsRepository.setDarkModeType(type)
            AppLogger.i(TAG, "深色背景类型已更新: $type")
        }
    }

    fun setHighContrast(enabled: Boolean) {
        _highContrast.value = enabled
        viewModelScope.launch {
            settingsRepository.setHighContrast(enabled)
            AppLogger.i(TAG, "高对比度已更新: $enabled")
        }
    }

    // ===== Phase 4: 远程服务控制 =====

    fun setHttpServerEnabled(enabled: Boolean) {
        _httpServerEnabled.value = enabled
        viewModelScope.launch {
            settingsRepository.setHttpServerEnabled(enabled)
            AppLogger.i(TAG, "HTTP Server 开关: $enabled")
        }
    }

    fun setHttpServerPort(port: Int) {
        _httpServerPort.value = port
        viewModelScope.launch {
            settingsRepository.setHttpServerPort(port)
            AppLogger.i(TAG, "HTTP Server 端口: $port")
        }
    }

    fun setDeviceName(name: String) {
        val normalized = name.trim().take(32)
        if (normalized.isEmpty()) return
        _deviceName.value = normalized
        viewModelScope.launch {
            settingsRepository.setDeviceName(normalized)
            AppLogger.i(TAG, "局域网设备名称已更新")
        }
    }

    fun toggleServer(context: android.content.Context) {
        val app = context.applicationContext
        if (_serverRunning.value) {
            stopServer(app)
            setHttpServerEnabled(false)
        } else {
            startServer(app)
            setHttpServerEnabled(true)
        }
    }

    private fun startServer(context: android.content.Context) {
        val intent = Intent(context, HttpServerForegroundService::class.java).apply {
            putExtra(HttpServerForegroundService.EXTRA_PORT, _httpServerPort.value)
            putExtra(HttpServerForegroundService.EXTRA_DEVICE_NAME, _deviceName.value)
        }
        ContextCompat.startForegroundService(context, intent)
        _serverRunning.value = true
        AppLogger.i(TAG, "HTTP Server 前台 Service 已发送启动")
    }

    private fun stopServer(context: android.content.Context) {
        val intent = Intent(context, HttpServerForegroundService::class.java).apply {
            action = HttpServerForegroundService.ACTION_STOP
        }
        context.startService(intent)
        _serverRunning.value = false
        _serverAddress.value = null
        AppLogger.i(TAG, "HTTP Server 前台 Service 已发送停止")
    }

    // ===== Phase 4: 远程缓存管理 =====

    private fun loadRemoteCacheStats() {
        viewModelScope.launch(Dispatchers.IO) {
            _remoteThumbCacheSize.value = "计算中…"
            _remoteImageCacheSize.value = "计算中…"
            try {
                val context = getApplication<RemoPhotoApp>()
                val thumbDir = context.cacheDir.resolve("remote_thumb_cache")
                val imageDir = context.cacheDir.resolve("remote_image_cache")
                _remoteThumbCacheSize.value = formatFileSize(dirSize(thumbDir))
                _remoteImageCacheSize.value = formatFileSize(dirSize(imageDir))
                AppLogger.i(TAG, "远程缓存统计完成: thumb=${_remoteThumbCacheSize.value}, image=${_remoteImageCacheSize.value}")
            } catch (e: Exception) {
                _remoteThumbCacheSize.value = "读取失败"
                _remoteImageCacheSize.value = "读取失败"
                AppLogger.e(TAG, "远程缓存统计失败", e)
            }
        }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearRemoteCaches(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = context.applicationContext as RemoPhotoApp
                app.dependencyContainer.remoteThumbnailLoader.memoryCache?.clear()
                app.dependencyContainer.remoteThumbnailLoader.diskCache?.clear()
                app.dependencyContainer.remoteImageLoader.memoryCache?.clear()
                app.dependencyContainer.remoteImageLoader.diskCache?.clear()
                loadRemoteCacheStats()
                AppLogger.i(TAG, "远程缩略图与原图的内存/磁盘缓存已清除")
            } catch (e: Exception) {
                AppLogger.e(TAG, "清除远程缓存失败", e)
            }
        }
    }

    private fun dirSize(dir: java.io.File): Long {
        return if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    // ===== 导入/导出 =====

    fun exportDatabase(context: android.content.Context, destUri: android.net.Uri) {
        viewModelScope.launch {
            AppLogger.i(TAG, "开始导出数据库到: $destUri")
            val success = com.remophoto.data.repository.DatabaseExporter.exportDatabase(context, destUri)
            if (success) {
                AppLogger.i(TAG, "数据库导出成功")
            } else {
                AppLogger.e(TAG, "数据库导出失败")
            }
        }
    }

    fun importDatabase(context: android.content.Context, sourceUri: android.net.Uri) {
        viewModelScope.launch {
            AppLogger.i(TAG, "开始从 $sourceUri 导入数据库")
            val success = com.remophoto.data.repository.DatabaseExporter.importDatabase(context, sourceUri)
            if (success) {
                AppLogger.i(TAG, "数据库导入成功，建议重启应用")
            } else {
                AppLogger.e(TAG, "数据库导入失败")
            }
        }
    }

    companion object {
        private const val TAG = "SettingsVM"
    }
}
