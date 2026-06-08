package com.remophoto.ui.albumlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remophoto.RemoPhotoApp
import com.remophoto.domain.model.Album
import com.remophoto.domain.model.SortOrder
import com.remophoto.data.local.entity.AlbumEntity
import com.remophoto.data.repository.AlbumRepository
import com.remophoto.data.repository.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 相册列表 ViewModel
 *
 * 管理相册列表的数据加载、排序、分页、树形结构构建。
 */
class AlbumListViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as RemoPhotoApp).dependencyContainer
    private val albumRepository: AlbumRepository = container.albumRepository
    private val settingsRepository: SettingsRepository = container.settingsRepository

    // ===== 状态 =====

    private val _allAlbums = MutableStateFlow<List<AlbumEntity>>(emptyList())
    private val _albumTree = MutableStateFlow<List<Album>>(emptyList())
    val albumTree: StateFlow<List<Album>> = _albumTree.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isEmpty = MutableStateFlow(true)
    val isEmpty: StateFlow<Boolean> = _isEmpty.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.DEFAULT)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _isGridView = MutableStateFlow(true)
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _albumsPerPage = MutableStateFlow(20)
    val albumsPerPage: StateFlow<Int> = _albumsPerPage.asStateFlow()

    init {
        loadSettings()
        loadAlbums()
    }

    // ===== 数据加载 =====

    private fun loadSettings() {
        viewModelScope.launch {
            settingsRepository.defaultSortOrder.collect { order ->
                _sortOrder.value = order
            }
        }
        viewModelScope.launch {
            settingsRepository.albumsPerPage.collect { count ->
                _albumsPerPage.value = count
            }
        }
    }

    fun loadAlbums() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                albumRepository.getRootAlbums().collect { rootAlbums ->
                    _allAlbums.value = rootAlbums
                    val tree = buildAlbumTree(rootAlbums)
                    _albumTree.value = tree
                    _isEmpty.value = tree.isEmpty()
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                _isLoading.value = false
            }
        }
    }

    /**
     * 手动刷新相册列表（用于添加仓库或扫描完成后刷新）
     */
    fun refresh() {
        loadAlbums()
    }

    // ===== 树形结构构建 =====

    /**
     * 将扁平相册列表构建为嵌套树
     */
    private suspend fun buildAlbumTree(allAlbums: List<AlbumEntity>): List<Album> {
        val albumMap = mutableMapOf<Long, Album>()
        val roots = mutableListOf<Album>()

        // 第一遍：所有相册转为 Album 领域模型
        for (entity in allAlbums) {
            albumMap[entity.id] = entity.toDomainModel(depth = 0)
        }

        // 第二遍：建立父子关系
        for (entity in allAlbums) {
            val album = albumMap[entity.id] ?: continue
            if (entity.parentAlbumId != null && albumMap.containsKey(entity.parentAlbumId)) {
                val parent = albumMap[entity.parentAlbumId]!!
                val childWithDepth = album.copy(depth = parent.depth + 1)
                albumMap[entity.id] = childWithDepth
            }
        }

        // 第三遍：根据 parentAlbumId 组装树
        val sorted = allAlbums.sortedBy { it.name }
        for (entity in sorted) {
            val album = albumMap[entity.id] ?: continue
            if (entity.parentAlbumId == null) {
                // 根级相册
                val withChildren = album.copy(children = getChildren(entity.id, albumMap, album.depth))
                roots.add(withChildren)
            }
        }

        return roots
    }

    private fun getChildren(
        parentId: Long,
        albumMap: Map<Long, Album>,
        parentDepth: Int
    ): List<Album> {
        return albumMap.values
            .filter { it.parentAlbumId == parentId }
            .map { it.copy(depth = parentDepth + 1) }
            .sortedBy { it.name }
            .map { child ->
                child.copy(children = getChildren(child.id, albumMap, child.depth))
            }
    }

    // ===== 排序 =====

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        viewModelScope.launch {
            settingsRepository.setDefaultSortOrder(order)
        }
        sortAlbums()
    }

    private fun sortAlbums() {
        val current = _albumTree.value
        val order = _sortOrder.value
        val sorted = when (order) {
            SortOrder.DATE_MODIFIED_ASC,
            SortOrder.DATE_MODIFIED_DESC -> current // 相册没有修改时间，保持名称排序
            SortOrder.NAME_ASC -> current.sortedBy { it.name.lowercase() }
            SortOrder.NAME_DESC -> current.sortedByDescending { it.name.lowercase() }
            SortOrder.SIZE_ASC -> current.sortedBy { it.imageCount }
            SortOrder.SIZE_DESC -> current.sortedByDescending { it.imageCount }
        }
        _albumTree.value = sorted
    }

    // ===== 布局切换 =====

    fun toggleLayoutMode() {
        _isGridView.value = !_isGridView.value
    }

    // ===== 分页 =====

    fun goToPage(page: Int) {
        val total = totalPages()
        if (page in 1..total) {
            _currentPage.value = page
        }
    }

    fun nextPage() {
        goToPage(_currentPage.value + 1)
    }

    fun previousPage() {
        goToPage(_currentPage.value - 1)
    }

    /**
     * 计算总页数（基于所有相册的扁平计数）
     */
    fun totalPages(): Int {
        val count = _allAlbums.value.size
        if (count == 0) return 1
        val pages = count / _albumsPerPage.value
        return if (count % _albumsPerPage.value == 0) pages else pages + 1
    }

    /**
     * 获取当前页的相册（扁平列表）
     */
    fun getPagedAlbums(): List<Album> {
        val all = flattenAlbums(_albumTree.value)
        val perPage = _albumsPerPage.value
        val offset = (_currentPage.value - 1) * perPage
        return if (offset >= all.size) emptyList()
        else all.drop(offset).take(perPage)
    }

    private fun flattenAlbums(albums: List<Album>): List<Album> {
        val result = mutableListOf<Album>()
        for (album in albums) {
            result.add(album)
            result.addAll(flattenAlbums(album.children))
        }
        return result
    }

    // ===== 工具方法 =====

    private fun AlbumEntity.toDomainModel(depth: Int): Album {
        return Album(
            id = id,
            name = name,
            directoryPath = directoryPath,
            repositoryId = repositoryId,
            parentAlbumId = parentAlbumId,
            coverImagePath = coverImagePath,
            sortOrder = sortOrder?.let { SortOrder.fromName(it) },
            imageCount = imageCount,
            depth = depth
        )
    }
}
