package com.remophoto.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remophoto.RemoPhotoApp
import com.remophoto.domain.model.ImageItem
import com.remophoto.domain.model.SortOrder
import com.remophoto.domain.usecase.AlbumCoverManager
import com.remophoto.data.local.entity.ImageEntity
import com.remophoto.data.repository.AlbumRepository
import com.remophoto.data.repository.ImageRepository
import com.remophoto.data.repository.SettingsRepository
import com.remophoto.util.AppLogger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 全屏浏览 ViewModel
 *
 * 管理全屏浏览的所有状态：
 * - 当前图片索引和图片列表
 * - 缩放状态（1× ~ 5×）
 * - UI 显隐切换
 * - 自动播放控制
 */
class FullScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as RemoPhotoApp).dependencyContainer
    private val imageRepository: ImageRepository = container.imageRepository
    private val albumRepository: AlbumRepository = container.albumRepository
    private val settingsRepository: SettingsRepository = container.settingsRepository

    // ===== 图片列表 =====

    private val _images = MutableStateFlow<List<ImageItem>>(emptyList())
    val images: StateFlow<List<ImageItem>> = _images.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    // ===== 缩放状态 =====

    private val _scale = MutableStateFlow(1f)
    val scale: StateFlow<Float> = _scale.asStateFlow()

    /** 记录每张图片的缩放状态（按索引） */
    private val scaleMap = mutableMapOf<Int, Float>()

    // ===== UI 显隐 =====

    private val _isUiVisible = MutableStateFlow(true)
    val isUiVisible: StateFlow<Boolean> = _isUiVisible.asStateFlow()

    // ===== 自动播放 =====

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playIntervalMs = MutableStateFlow(3000L)
    val playIntervalMs: StateFlow<Long> = _playIntervalMs.asStateFlow()

    private var currentAlbumId: Long = 0L

    init {
        // 从设置中读取自动播放间隔
        viewModelScope.launch {
            settingsRepository.slideshowInterval.collect { seconds ->
                _playIntervalMs.value = seconds * 1000L
            }
        }
    }

    // ===== 数据加载 =====

    fun loadImages(albumId: Long, initialIndex: Int) {
        // 进入新相册时清理旧数据
        if (currentAlbumId != albumId) {
            _images.value = emptyList()
        }
        currentAlbumId = albumId
        viewModelScope.launch {
            try {
                // 读取相册自定义排序
                val albumEntity = albumRepository.getAlbumById(albumId)
                val albumSortOrder = albumEntity?.sortOrder?.let { SortOrder.fromName(it) }
                // 读取全局排序
                val globalSortOrder = settingsRepository.defaultSortOrder.first()
                val effectiveSort = albumSortOrder ?: globalSortOrder

                imageRepository.getImagesByAlbum(albumId).collect { entities ->
                    val sorted = sortImageEntities(entities, effectiveSort)
                    _images.value = sorted.map { it.toDomainModel() }
                    AppLogger.d(TAG,
                        "全屏图片排序: albumId=$albumId, sort=${effectiveSort.displayName}, " +
                        "count=${sorted.size}"
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载图片失败", e)
            }
        }
        _currentIndex.value = initialIndex
        _scale.value = 1f
    }

    // ===== 导航 =====

    fun nextImage() {
        val list = _images.value
        if (list.isEmpty()) return
        // 保存当前缩放
        scaleMap[_currentIndex.value] = _scale.value
        // 切换到下一张
        _currentIndex.value = (_currentIndex.value + 1) % list.size
        // 恢复或重置缩放
        _scale.value = scaleMap[_currentIndex.value] ?: 1f
    }

    fun previousImage() {
        val list = _images.value
        if (list.isEmpty()) return
        scaleMap[_currentIndex.value] = _scale.value
        _currentIndex.value = (_currentIndex.value - 1 + list.size) % list.size
        _scale.value = scaleMap[_currentIndex.value] ?: 1f
    }

    fun goToImage(index: Int) {
        val list = _images.value
        if (index in list.indices) {
            scaleMap[_currentIndex.value] = _scale.value
            _currentIndex.value = index
            _scale.value = scaleMap[index] ?: 1f
        }
    }

    // ===== 缩放 =====

    fun setScale(newScale: Float) {
        _scale.value = newScale.coerceIn(1f, 5f)
    }

    fun resetScale() {
        _scale.value = 1f
    }

    fun toggleZoom() {
        // 双击：1× ↔ 3× 切换
        if (_scale.value <= 1.5f) {
            _scale.value = 3f
        } else {
            _scale.value = 1f
        }
    }

    // ===== UI 显隐 =====

    fun toggleUiVisibility() {
        _isUiVisible.value = !_isUiVisible.value
    }

    fun showUi() {
        _isUiVisible.value = true
    }

    fun hideUi() {
        _isUiVisible.value = false
    }

    // ===== 自动播放 =====

    fun togglePlay() {
        _isPlaying.value = !_isPlaying.value
    }

    fun setPlayInterval(ms: Long) {
        _playIntervalMs.value = ms
    }

    fun stopPlaying() {
        _isPlaying.value = false
    }

    // ===== 当前图片 =====

    val currentImage: ImageItem?
        get() {
            val list = _images.value
            val index = _currentIndex.value
            return if (index in list.indices) list[index] else null
        }

    // ===== 设为封面 =====

    fun setAsCover(image: ImageItem, coverManager: AlbumCoverManager) {
        viewModelScope.launch {
            try {
                coverManager.setCustomCover(image.albumId, image.filePath)
                AppLogger.i("FullScreenVM", "设为封面成功: albumId=${image.albumId}, path=${image.filePath}")
            } catch (e: Exception) {
                AppLogger.e("FullScreenVM", "设为封面失败", e)
            }
        }
    }

    // ===== 排序 =====

    private fun sortImageEntities(entities: List<ImageEntity>, order: SortOrder): List<ImageEntity> {
        return when (order) {
            SortOrder.NAME_ASC -> entities.sortedBy { it.fileName.lowercase() }
            SortOrder.NAME_DESC -> entities.sortedByDescending { it.fileName.lowercase() }
            SortOrder.DATE_MODIFIED_ASC -> entities.sortedBy { it.lastModified }
            SortOrder.DATE_MODIFIED_DESC -> entities.sortedByDescending { it.lastModified }
            SortOrder.SIZE_ASC -> entities.sortedBy { it.fileSize }
            SortOrder.SIZE_DESC -> entities.sortedByDescending { it.fileSize }
        }
    }

    // ===== 映射 =====

    private fun ImageEntity.toDomainModel(): ImageItem {
        return ImageItem(
            id = id,
            filePath = filePath,
            fileName = fileName,
            fileSize = fileSize,
            lastModified = lastModified,
            mimeType = mimeType,
            width = width,
            height = height,
            albumId = albumId,
            repositoryId = repositoryId
        )
    }

    companion object {
        private const val TAG = "FullScreenVM"
    }
}
