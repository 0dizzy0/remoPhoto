package com.remophoto.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remophoto.RemoPhotoApp
import com.remophoto.data.repository.ImageRepository
import com.remophoto.data.repository.SettingsRepository
import com.remophoto.domain.model.SortOrder
import com.remophoto.util.AppLogger
import kotlinx.coroutines.flow.*
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

    private val _albumsPerPage = MutableStateFlow(20)
    val albumsPerPage: StateFlow<Int> = _albumsPerPage.asStateFlow()

    private val _slideshowInterval = MutableStateFlow(3)
    val slideshowInterval: StateFlow<Int> = _slideshowInterval.asStateFlow()

    private val _useVolumeKeys = MutableStateFlow(true)
    val useVolumeKeys: StateFlow<Boolean> = _useVolumeKeys.asStateFlow()

    // 存储空间
    private val _totalImageCount = MutableStateFlow(0)
    val totalImageCount: StateFlow<Int> = _totalImageCount.asStateFlow()

    private val _totalStorageSize = MutableStateFlow(0L)
    val totalStorageSize: StateFlow<Long> = _totalStorageSize.asStateFlow()

    // ===== 初始化：从 DataStore 加载 =====

    init {
        viewModelScope.launch {
            settingsRepository.themeMode.collect { _themeMode.value = it }
        }
        viewModelScope.launch {
            settingsRepository.defaultSortOrder.collect { _defaultSortOrder.value = it }
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

        AppLogger.i(TAG, "SettingsViewModel 初始化完成")
        loadStorageStats()
    }

    private fun loadStorageStats() {
        viewModelScope.launch {
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

    companion object {
        private const val TAG = "SettingsVM"
    }
}
