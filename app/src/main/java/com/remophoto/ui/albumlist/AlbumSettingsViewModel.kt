package com.remophoto.ui.albumlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remophoto.RemoPhotoApp
import com.remophoto.data.local.entity.AlbumEntity
import com.remophoto.data.local.entity.CategoryEntity
import com.remophoto.data.repository.AlbumRepository
import com.remophoto.data.repository.ImageRepository
import com.remophoto.domain.model.SortOrder
import com.remophoto.domain.usecase.AlbumCoverManager
import com.remophoto.domain.usecase.CategoryManager
import com.remophoto.util.AppLogger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 单相册设置 ViewModel
 *
 * 管理单个相册的覆盖设置：排序方式、封面、分类关联。
 */
class AlbumSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RemoPhotoApp
    private val albumRepository: AlbumRepository = app.dependencyContainer.albumRepository
    private val imageRepository: ImageRepository = app.dependencyContainer.imageRepository
    private val albumCoverManager: AlbumCoverManager = app.dependencyContainer.albumCoverManager
    private val categoryManager: CategoryManager = app.dependencyContainer.categoryManager

    private val _album = MutableStateFlow<AlbumEntity?>(null)
    val album: StateFlow<AlbumEntity?> = _album.asStateFlow()

    private val _sortOrder = MutableStateFlow<SortOrder?>(null)
    val sortOrder: StateFlow<SortOrder?> = _sortOrder.asStateFlow()

    private val _isCustomSort = MutableStateFlow(false)
    val isCustomSort: StateFlow<Boolean> = _isCustomSort.asStateFlow()

    private val _imageCount = MutableStateFlow(0)
    val imageCount: StateFlow<Int> = _imageCount.asStateFlow()

    private val _totalSize = MutableStateFlow(0L)
    val totalSize: StateFlow<Long> = _totalSize.asStateFlow()

    private val _albumCategories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val albumCategories: StateFlow<List<CategoryEntity>> = _albumCategories.asStateFlow()

    private val _allCategories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val allCategories: StateFlow<List<CategoryEntity>> = _allCategories.asStateFlow()

    private var albumId: Long = 0L

    fun loadAlbum(albumId: Long) {
        this.albumId = albumId
        viewModelScope.launch {
            try {
                val entity = albumRepository.getAlbumById(albumId)
                _album.value = entity

                if (entity != null) {
                    val order = entity.sortOrder?.let { SortOrder.fromName(it) }
                    _sortOrder.value = order
                    _isCustomSort.value = entity.sortOrder != null

                    _imageCount.value = imageRepository.getImageCountByAlbum(albumId)
                    _albumCategories.value = categoryManager.getCategoriesForAlbum(albumId)
                }

                // 加载所有分类
                categoryManager.getAllCategories().collect { categories ->
                    _allCategories.value = categories
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载相册设置失败", e)
            }
        }
    }

    fun setCustomSortOrder(order: SortOrder?) {
        viewModelScope.launch {
            try {
                _sortOrder.value = order
                _isCustomSort.value = order != null
                albumRepository.updateSortOrder(albumId, order?.name)
                AppLogger.i(TAG, "相册排序已更新: albumId=$albumId, sortOrder=${order?.displayName ?: "使用全局"}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "更新排序失败", e)
            }
        }
    }

    fun toggleCategory(categoryId: Long) {
        viewModelScope.launch {
            try {
                val current = _albumCategories.value.map { it.id }.toSet()
                if (categoryId in current) {
                    // 移除
                    categoryManager.removeAlbumFromCategory(albumId, categoryId)
                } else {
                    // 添加
                    categoryManager.assignAlbumToCategory(albumId, categoryId)
                }
                _albumCategories.value = categoryManager.getCategoriesForAlbum(albumId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "切换分类关联失败", e)
            }
        }
    }

    fun clearCustomCover() {
        viewModelScope.launch {
            try {
                albumCoverManager.clearCustomCover(albumId)
                val entity = albumRepository.getAlbumById(albumId)
                _album.value = entity
            } catch (e: Exception) {
                AppLogger.e(TAG, "清除封面失败", e)
            }
        }
    }

    companion object {
        private const val TAG = "AlbumSettingsVM"
    }
}
