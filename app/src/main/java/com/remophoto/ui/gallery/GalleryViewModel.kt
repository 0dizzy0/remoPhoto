package com.remophoto.ui.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remophoto.RemoPhotoApp
import com.remophoto.domain.model.Album
import com.remophoto.domain.model.ImageItem
import com.remophoto.data.local.entity.AlbumEntity
import com.remophoto.data.local.entity.ImageEntity
import com.remophoto.data.repository.AlbumRepository
import com.remophoto.data.repository.ImageRepository
import com.remophoto.data.repository.SettingsRepository
import com.remophoto.domain.model.SortOrder
import com.remophoto.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图片网格 ViewModel
 *
 * 管理相册内图片列表的加载、网格/列表切换。
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as RemoPhotoApp).dependencyContainer
    private val imageRepository: ImageRepository = container.imageRepository
    private val albumRepository: AlbumRepository = container.albumRepository
    private val settingsRepository: SettingsRepository = container.settingsRepository

    // ===== 状态 =====

    private val _images = MutableStateFlow<List<ImageItem>>(emptyList())
    val images: StateFlow<List<ImageItem>> = _images.asStateFlow()

    private val _album = MutableStateFlow<Album?>(null)
    val album: StateFlow<Album?> = _album.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isGridView = MutableStateFlow(true)
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    private var currentAlbumId: Long = 0L

    /** 当前加载协程，用于取消上一次未完成的加载 */
    private var loadJob: Job? = null

    // ===== 数据加载 =====

    fun loadAlbumAndImages(albumId: Long) {
        // 进入新相册时清理旧数据，避免闪烁上一相册的图片
        if (currentAlbumId != albumId) {
            _images.value = emptyList()
            _album.value = null
        }
        currentAlbumId = albumId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                // 加载相册信息
                val albumEntity = albumRepository.getAlbumById(albumId)
                _album.value = albumEntity?.let { entity ->
                    Album(
                        id = entity.id,
                        name = entity.name,
                        directoryPath = entity.directoryPath,
                        repositoryId = entity.repositoryId,
                        parentAlbumId = entity.parentAlbumId,
                        coverImagePath = entity.coverImagePath,
                        sortOrder = entity.sortOrder?.let {
                            com.remophoto.domain.model.SortOrder.fromName(it)
                        },
                        imageCount = entity.imageCount
                    )
                }

                // 确定排序方式：单相册设置 > 全局设置
                val albumSortOrder = albumEntity?.sortOrder?.let { SortOrder.fromName(it) }
                val globalSortOrder = settingsRepository.defaultSortOrder.first()

                // 加载图片列表（Flow 响应式），按排序方式排序
                imageRepository.getImagesByAlbum(albumId).collect { imageEntities ->
                    val effectiveSort = albumSortOrder ?: globalSortOrder
                    val sorted = withContext(Dispatchers.Default) {
                        sortImageEntities(imageEntities, effectiveSort)
                    }
                    _images.value = sorted.map { ImageItem.fromEntity(it) }
                    _isLoading.value = false
                    AppLogger.d(TAG,
                        "图片排序: albumId=$albumId, sort=${effectiveSort.displayName}, " +
                        "custom=${albumSortOrder != null}"
                    )
                }
            } catch (e: Exception) {
                _isLoading.value = false
            }
        }
    }

    fun toggleLayoutMode() {
        _isGridView.value = !_isGridView.value
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

    companion object {
        private const val TAG = "GalleryVM"
    }
}
