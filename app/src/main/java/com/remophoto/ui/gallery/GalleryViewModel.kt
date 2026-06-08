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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 图片网格 ViewModel
 *
 * 管理相册内图片列表的加载、网格/列表切换。
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as RemoPhotoApp).dependencyContainer
    private val imageRepository: ImageRepository = container.imageRepository
    private val albumRepository: AlbumRepository = container.albumRepository

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

    // ===== 数据加载 =====

    fun loadAlbumAndImages(albumId: Long) {
        // 进入新相册时清理旧数据，避免闪烁上一相册的图片
        if (currentAlbumId != albumId) {
            _images.value = emptyList()
            _album.value = null
        }
        currentAlbumId = albumId
        viewModelScope.launch {
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

                // 加载图片列表（Flow 响应式）
                imageRepository.getImagesByAlbum(albumId).collect { imageEntities ->
                    _images.value = imageEntities.map { it.toDomainModel() }
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                _isLoading.value = false
            }
        }
    }

    fun toggleLayoutMode() {
        _isGridView.value = !_isGridView.value
    }

    // ===== 映射方法 =====

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
}
