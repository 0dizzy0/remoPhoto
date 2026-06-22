package com.remophoto.ui.albumlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remophoto.RemoPhotoApp
import com.remophoto.domain.model.Album
import com.remophoto.domain.model.SortOrder
import com.remophoto.domain.model.AlbumSortOrder
import com.remophoto.domain.usecase.CategoryManager
import com.remophoto.data.local.entity.AlbumEntity
import com.remophoto.data.local.entity.CategoryEntity
import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RepositoryEntity
import com.remophoto.data.repository.AlbumRepository
import com.remophoto.data.local.dao.RepositoryDao
import com.remophoto.data.repository.SettingsRepository
import com.remophoto.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 相册列表 ViewModel
 *
 * 管理相册列表的数据加载、排序、分页、树形结构构建。
 */
class AlbumListViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as RemoPhotoApp).dependencyContainer
    private val albumRepository: AlbumRepository = container.albumRepository
    private val repositoryDao: RepositoryDao = container.repositoryDao
    private val settingsRepository: SettingsRepository = container.settingsRepository
    private val categoryManager: CategoryManager = container.categoryManager
    // Phase 4: 远程仓库
    private val remoteConnectionDao = container.remoteConnectionDao
    private val syncRemoteUseCase = container.syncRemoteRepositoryUseCase

    // ===== 状态 =====

    private val _allAlbums = MutableStateFlow<List<AlbumEntity>>(emptyList())
    private val _albumTree = MutableStateFlow<List<Album>>(emptyList())
    val albumTree: StateFlow<List<Album>> = _albumTree.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isEmpty = MutableStateFlow(true)
    val isEmpty: StateFlow<Boolean> = _isEmpty.asStateFlow()

    private val _sortOrder = MutableStateFlow(AlbumSortOrder.DEFAULT)
    val sortOrder: StateFlow<AlbumSortOrder> = _sortOrder.asStateFlow()

    private val _isGridView = MutableStateFlow(true)
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _totalPages = MutableStateFlow(1)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    private val _albumsPerPage = MutableStateFlow(20)
    val albumsPerPage: StateFlow<Int> = _albumsPerPage.asStateFlow()

    // 分类筛选状态
    private val _filterCategoryId = MutableStateFlow<Long?>(null)
    val filterCategoryId: StateFlow<Long?> = _filterCategoryId.asStateFlow()

    private val _filterCategoryName = MutableStateFlow<String?>(null)
    val filterCategoryName: StateFlow<String?> = _filterCategoryName.asStateFlow()

    // 多选状态
    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedAlbumIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedAlbumIds: StateFlow<Set<Long>> = _selectedAlbumIds.asStateFlow()

    // 分类列表（供分类选择器弹窗使用）
    private val _allCategories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val allCategories: StateFlow<List<CategoryEntity>> = _allCategories.asStateFlow()

    /** 当前页的扁平相册列表（预计算，避免 UI 线程递归） */
    private val _pagedAlbums = MutableStateFlow<List<Album>>(emptyList())
    val pagedAlbums: StateFlow<List<Album>> = _pagedAlbums.asStateFlow()
    private var flatAlbums: List<Album> = emptyList()

    // ===== 仓库层级选择 =====

    /** 仓库列表 */
    private val _repoList = MutableStateFlow<List<RepositoryEntity>>(emptyList())
    val repoList: StateFlow<List<RepositoryEntity>> = _repoList.asStateFlow()

    /** 当前选中的仓库 ID（null = 显示仓库列表） */
    private val _selectedRepoId = MutableStateFlow<Long?>(null)
    val selectedRepoId: StateFlow<Long?> = _selectedRepoId.asStateFlow()

    /** 当前选中的仓库名 */
    private val _selectedRepoName = MutableStateFlow<String?>(null)
    val selectedRepoName: StateFlow<String?> = _selectedRepoName.asStateFlow()

    // Phase 4: 远程仓库状态
    /** 远程连接状态映射 (connectionId -> ConnectionStatus) */
    private val _remoteStatusMap = MutableStateFlow<Map<Long, ConnectionStatus>>(emptyMap())
    val remoteStatusMap: StateFlow<Map<Long, ConnectionStatus>> = _remoteStatusMap.asStateFlow()

    private val _isRefreshingRemote = MutableStateFlow(false)
    val isRefreshingRemote: StateFlow<Boolean> = _isRefreshingRemote.asStateFlow()

    // 加载任务引用，用于取消旧协程防止竞态覆盖
    private var loadJob: Job? = null

    init {
        loadSettings()
        loadRepos()
    }

    // ===== 数据加载 =====

    private fun loadSettings() {
        viewModelScope.launch {
            settingsRepository.albumSortOrder.collect { order ->
                _sortOrder.value = order
                sortAlbums()
            }
        }
        viewModelScope.launch {
            settingsRepository.albumsPerPage.collect { count ->
                if (_albumsPerPage.value != count) {
                    _albumsPerPage.value = count
                    _currentPage.value = 1
                    recomputePagedAlbums()
                }
            }
        }
    }

    private fun loadRepos() {
        viewModelScope.launch {
            repositoryDao.getAllRepositories().collect { repos ->
                _repoList.value = repos
            }
        }
        // Phase 4: 加载远程连接状态
        viewModelScope.launch {
            remoteConnectionDao.getAllConnections().collect { connections ->
                _remoteStatusMap.value = connections.associate { it.id to it.status }
            }
        }
    }

    /** 判断仓库是否为远程仓库 */
    fun isRemoteRepo(repo: RepositoryEntity): Boolean = repo.remoteConnectionId != null

    /** 本地仓库列表 */
    fun localRepos(): List<RepositoryEntity> = _repoList.value.filter { it.remoteConnectionId == null }

    /** 远程仓库列表 */
    fun remoteRepos(): List<RepositoryEntity> = _repoList.value.filter { it.remoteConnectionId != null }

    /** 获取远程仓库的连接状态 */
    fun getRemoteStatus(repo: RepositoryEntity): ConnectionStatus {
        val connId = repo.remoteConnectionId ?: return ConnectionStatus.DISCONNECTED
        return _remoteStatusMap.value[connId] ?: ConnectionStatus.DISCONNECTED
    }

    fun loadAlbums() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val repoId = _selectedRepoId.value
                if (repoId != null) {
                    // 必须读取仓库内全部相册，分页和父子树才能使用同一数据集。
                    val allAlbums = albumRepository.getAlbumsByRepositoryList(repoId)
                    _allAlbums.value = allAlbums
                    val tree = buildAlbumTree(allAlbums)
                    _albumTree.value = tree
                    _isEmpty.value = tree.isEmpty()
                    _isLoading.value = false
                    recomputePagedAlbums()
                } else {
                    // 全量加载（响应式 Flow）
                    albumRepository.getAllAlbums().collect { allAlbums ->
                        _allAlbums.value = allAlbums
                        val tree = buildAlbumTree(allAlbums)
                        _albumTree.value = tree
                        _isEmpty.value = tree.isEmpty()
                        _isLoading.value = false
                        recomputePagedAlbums()
                    }
                }
            } catch (e: CancellationException) {
                AppLogger.d(TAG, "相册加载任务已被新请求替换")
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载相册列表失败", e)
                _isLoading.value = false
            }
        }
    }

    /** 选择仓库，加载该仓库的相册（本地或远程） */
    fun selectRepo(repoId: Long, repoName: String) {
        _albumTree.value = emptyList()
        _pagedAlbums.value = emptyList()
        _isLoading.value = true
        _selectedRepoId.value = repoId
        _selectedRepoName.value = repoName
        _currentPage.value = 1
        _totalPages.value = 1

        // Phase 4: 检查是否为远程仓库，触发同步
        val repo = _repoList.value.find { it.id == repoId }
        if (repo?.remoteConnectionId != null) {
            loadRemoteAlbums(repo, forceRefresh = false)
        } else {
            loadAlbums()
        }
    }

    /**
     * Phase 4: 同步远程仓库相册并加载
     */
    private fun loadRemoteAlbums(repo: RepositoryEntity, forceRefresh: Boolean) {
        if (repo.remoteConnectionId == null) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            var hasCachedAlbums = false
            try {
                val cachedAlbums = albumRepository.getAlbumsByRepositoryList(repo.id)
                hasCachedAlbums = cachedAlbums.isNotEmpty()
                if (hasCachedAlbums) {
                    applyAlbumEntities(cachedAlbums)
                    AppLogger.i(TAG, "远程相册缓存已展示: repo=${repo.name}, count=${cachedAlbums.size}")
                }

                val cacheAgeMs = System.currentTimeMillis() - repo.lastScanTime
                val cacheFresh = repo.lastScanTime > 0L && cacheAgeMs < REMOTE_CACHE_TTL_MS
                if (hasCachedAlbums && cacheFresh && !forceRefresh) {
                    AppLogger.i(TAG, "远程相册缓存仍有效，跳过同步: repo=${repo.name}, ageMs=$cacheAgeMs")
                    return@launch
                }

                _isLoading.value = !hasCachedAlbums
                _isRefreshingRemote.value = hasCachedAlbums
                val conn = remoteConnectionDao.getConnectionById(repo.remoteConnectionId)
                    ?: return@launch
                val syncedCount = syncRemoteUseCase.syncAlbums(conn, repo.id)
                AppLogger.i(TAG, "远程同步完成: ${syncedCount} 个相册, repo=${repo.name}")

                if (_selectedRepoId.value == repo.id) {
                    applyAlbumEntities(albumRepository.getAlbumsByRepositoryList(repo.id))
                }
            } catch (e: CancellationException) {
                AppLogger.d(TAG, "远程仓库同步任务已取消: ${repo.name}")
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "远程仓库同步失败: ${repo.name}", e)
                if (!hasCachedAlbums) _isEmpty.value = true
            } finally {
                _isLoading.value = false
                _isRefreshingRemote.value = false
            }
        }
    }

    fun refreshSelectedRemoteRepository() {
        val repoId = _selectedRepoId.value ?: return
        val repo = _repoList.value.find { it.id == repoId && it.remoteConnectionId != null } ?: return
        AppLogger.i(TAG, "用户手动刷新远程仓库: repo=${repo.name}")
        loadRemoteAlbums(repo, forceRefresh = true)
    }

    private suspend fun applyAlbumEntities(allAlbums: List<AlbumEntity>) {
        _allAlbums.value = allAlbums
        val tree = buildAlbumTree(allAlbums)
        _albumTree.value = tree
        _isEmpty.value = tree.isEmpty()
        recomputePagedAlbums()
    }

    /** 返回仓库列表 */
    fun clearRepoSelection() {
        _albumTree.value = emptyList()
        _pagedAlbums.value = emptyList()
        _selectedRepoId.value = null
        _selectedRepoName.value = null
        _currentPage.value = 1
        _totalPages.value = 1
        _isRefreshingRemote.value = false
        loadAlbums()
    }

    /**
     * 手动刷新相册列表（用于添加仓库或扫描完成后刷新）
     */
    fun refresh() {
        if (_filterCategoryId.value != null) {
            loadAlbumsByCategory(_filterCategoryId.value!!, _filterCategoryName.value ?: "")
        } else {
            loadAlbums()
        }
    }

    /**
     * 按分类加载相册
     */
    fun loadAlbumsByCategory(categoryId: Long, categoryName: String) {
        _filterCategoryId.value = categoryId
        _filterCategoryName.value = categoryName
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val categoryAlbums = categoryManager.getAlbumIdsForCategory(categoryId).toSet()
                // 数据库中的 CONNECTED 只是上次状态；进入分类前并发验证，避免离线远程相册误显示。
                val remoteConnections = remoteConnectionDao.getAllConnectionsList()
                coroutineScope {
                    remoteConnections.map { connection ->
                        async { syncRemoteUseCase.checkConnection(connection) }
                    }.awaitAll()
                }
                AppLogger.i(TAG, "分类加载前远程状态检查完成: 连接数=${remoteConnections.size}")
                combine(
                    albumRepository.getAllAlbums(),
                    repositoryDao.getAllRepositories(),
                    remoteConnectionDao.getAllConnections()
                ) { albums, repositories, connections ->
                    val connectionStatus = connections.associate { it.id to it.status }
                    val visibleRepositoryIds = repositories
                        .filter { repo ->
                            repo.remoteConnectionId == null ||
                                connectionStatus[repo.remoteConnectionId] == ConnectionStatus.CONNECTED
                        }
                        .mapTo(mutableSetOf()) { it.id }
                    albums.filter { it.repositoryId in visibleRepositoryIds }
                }.collect { visibleAlbums ->
                    _allAlbums.value = visibleAlbums
                    val tree = buildAlbumTree(visibleAlbums)
                    // 筛选：只保留该分类下的相册及其祖先
                    val filteredTree = filterTreeByCategory(tree, categoryAlbums)
                    _albumTree.value = filteredTree
                    _isEmpty.value = filteredTree.isEmpty()
                    _isLoading.value = false
                    recomputePagedAlbums()
                    AppLogger.i(TAG,
                        "分类筛选: category=\"$categoryName\"(id=$categoryId), " +
                        "匹配相册=${categoryAlbums.size}, 可访问相册=${visibleAlbums.size}, 显示树=${filteredTree.size}"
                    )
                }
            } catch (e: CancellationException) {
                AppLogger.d(TAG, "分类相册加载任务已被新请求替换")
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "按分类加载相册失败", e)
                _isLoading.value = false
            }
        }
    }

    /**
     * 清除分类筛选，回到全部相册
     */
    fun clearCategoryFilter() {
        _filterCategoryId.value = null
        _filterCategoryName.value = null
        loadAlbums()
    }

    /**
     * 筛选相册树：保留属于分类的相册及其祖先
     */
    private suspend fun filterTreeByCategory(tree: List<Album>, categoryAlbumIds: Set<Long>): List<Album> = withContext(Dispatchers.Default) {
        tree.mapNotNull { album -> filterNode(album, categoryAlbumIds) }
    }

    private fun filterNode(album: Album, categoryAlbumIds: Set<Long>): Album? {
        val filteredChildren = album.children.mapNotNull { filterNode(it, categoryAlbumIds) }
        val isMatch = album.id in categoryAlbumIds
        val hasMatchingChildren = filteredChildren.isNotEmpty()
        return if (isMatch || hasMatchingChildren) {
            album.copy(children = filteredChildren)
        } else {
            null
        }
    }

    // ===== 树形结构构建 =====

    /**
     * 将扁平相册列表构建为嵌套树
     */
    private suspend fun buildAlbumTree(allAlbums: List<AlbumEntity>): List<Album> = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()
        val ids = allAlbums.mapTo(mutableSetOf()) { it.id }
        val childrenByParent = allAlbums.groupBy { it.parentAlbumId }

        fun buildNode(entity: AlbumEntity, depth: Int): Album {
            val children = childrenByParent[entity.id].orEmpty().map { buildNode(it, depth + 1) }
            return entity.toDomainModel(depth).copy(children = children)
        }

        val roots = allAlbums
            .filter { it.parentAlbumId == null || it.parentAlbumId !in ids }
            .map { buildNode(it, 0) }
        val sorted = sortAlbumTree(roots, _sortOrder.value)
        AppLogger.i(
            TAG,
            "相册树构建完成: albums=${allAlbums.size}, roots=${sorted.size}, " +
                "elapsedMs=${System.currentTimeMillis() - startedAt}"
        )
        sorted
    }

    // ===== 排序 =====

    fun setSortOrder(order: AlbumSortOrder) {
        _sortOrder.value = order
        viewModelScope.launch {
            settingsRepository.setAlbumSortOrder(order)
        }
        sortAlbums()
    }

    private fun sortAlbums() {
        val current = _albumTree.value
        val order = _sortOrder.value
        val sorted = sortAlbumTree(current, order)
        _albumTree.value = sorted
        viewModelScope.launch {
            recomputePagedAlbums()
            AppLogger.i(TAG, "相册列表已排序: ${order.displayName}, 相册数=${flatAlbums.size}")
        }
    }

    private fun sortAlbumTree(albums: List<Album>, order: AlbumSortOrder): List<Album> {
        val withSortedChildren = albums.map { album ->
            album.copy(children = sortAlbumTree(album.children, order))
        }
        return when (order) {
            AlbumSortOrder.NAME_ASC -> withSortedChildren.sortedBy { it.name.lowercase() }
            AlbumSortOrder.NAME_DESC -> withSortedChildren.sortedByDescending { it.name.lowercase() }
            AlbumSortOrder.MODIFIED_ASC -> withSortedChildren.sortedWith(
                compareBy<Album> { it.lastModified }.thenBy { it.name.lowercase() }
            )
            AlbumSortOrder.MODIFIED_DESC -> withSortedChildren.sortedWith(
                compareByDescending<Album> { it.lastModified }.thenBy { it.name.lowercase() }
            )
            AlbumSortOrder.IMAGE_COUNT_ASC -> withSortedChildren.sortedBy { it.imageCount }
            AlbumSortOrder.IMAGE_COUNT_DESC -> withSortedChildren.sortedByDescending { it.imageCount }
        }
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
            viewModelScope.launch { recomputePagedAlbums() }
        }
    }

    /** 模糊查找首个相册并切换到其所在页，不改变当前列表内容。 */
    fun locateAlbumByName(query: String): Album? {
        val normalized = query.trim()
        val location = AlbumListLocator.locate(flatAlbums, normalized, _albumsPerPage.value)
        if (location == null) {
            AppLogger.i(TAG, "相册定位未命中: query=\"$normalized\"")
            return null
        }
        _currentPage.value = location.page
        updatePageSlice()
        AppLogger.i(
            TAG,
            "相册定位成功: query=\"$normalized\", albumId=${location.album.id}, page=${location.page}"
        )
        return location.album
    }

    fun nextPage() {
        goToPage(_currentPage.value + 1)
    }

    fun previousPage() {
        goToPage(_currentPage.value - 1)
    }

    /**
     * 计算总页数（基于平铺后的相册总数，与 recomputePagedAlbums 同口径）
     */
    fun totalPages(): Int {
        return _totalPages.value
    }

    /**
     * 获取当前页的相册（扁平列表）
     */
    fun getPagedAlbums(): List<Album> {
        return _pagedAlbums.value
    }

    private suspend fun flattenAlbums(albums: List<Album>): List<Album> = withContext(Dispatchers.Default) {
        val result = mutableListOf<Album>()
        for (album in albums) {
            result.add(album)
            result.addAll(flattenAlbums(album.children))
        }
        result
    }

    /**
     * 预计算当前页的扁平相册列表（异步执行，避免 UI 线程递归）
     */
    private suspend fun recomputePagedAlbums() {
        flatAlbums = flattenAlbums(_albumTree.value)
        val computedPages = AlbumListLocator.pageCount(flatAlbums.size, _albumsPerPage.value)
        _totalPages.value = computedPages
        if (_currentPage.value > computedPages) {
            _currentPage.value = computedPages
        }
        updatePageSlice()
    }

    private fun updatePageSlice() {
        val perPage = _albumsPerPage.value
        val offset = (_currentPage.value - 1) * perPage
        _pagedAlbums.value = if (offset >= flatAlbums.size) emptyList()
        else flatAlbums.drop(offset).take(perPage)
    }

    // ===== 工具方法 =====

    private fun AlbumEntity.toDomainModel(depth: Int): Album {
        val model = Album(
            id = id,
            name = name,
            directoryPath = directoryPath,
            repositoryId = repositoryId,
            parentAlbumId = parentAlbumId,
            coverImagePath = coverImagePath,
            sortOrder = sortOrder?.let { SortOrder.fromName(it) },
            imageCount = imageCount,
            lastModified = lastModified,
            depth = depth
        )
        return model
    }

    // ===== 多选操作 =====

    /** 进入多选模式并选中指定相册 */
    fun enterSelectionMode(albumId: Long) {
        _selectionMode.value = true
        _selectedAlbumIds.value = setOf(albumId)
        AppLogger.i(TAG, "进入多选模式，初始选中: albumId=$albumId")
    }

    /** 退出多选模式并清空选中 */
    fun exitSelectionMode() {
        _selectionMode.value = false
        _selectedAlbumIds.value = emptySet()
        AppLogger.i(TAG, "退出多选模式")
    }

    /** 切换相册选中状态 */
    fun toggleSelection(albumId: Long) {
        val current = _selectedAlbumIds.value
        _selectedAlbumIds.value = if (albumId in current) {
            current - albumId
        } else {
            current + albumId
        }
    }

    /** 加载全部分类列表 */
    fun loadAllCategories() {
        viewModelScope.launch {
            try {
                categoryManager.getAllCategories().collect { list ->
                    _allCategories.value = list
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载分类列表失败", e)
            }
        }
    }

    /** 将选中的相册批量添加到指定分类 */
    fun addSelectedToCategory(categoryId: Long) {
        val albumIds = _selectedAlbumIds.value
        if (albumIds.isEmpty()) return
        viewModelScope.launch {
            try {
                var successCount = 0
                for (albumId in albumIds) {
                    categoryManager.assignAlbumToCategory(albumId, categoryId)
                    successCount++
                }
                AppLogger.i(TAG,
                    "批量添加到分类: categoryId=$categoryId, 成功=$successCount/${albumIds.size}"
                )
                // 完成后退出多选模式
                exitSelectionMode()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "批量添加到分类失败: categoryId=$categoryId", e)
            }
        }
    }

    companion object {
        private const val TAG = "AlbumList"
        private const val REMOTE_CACHE_TTL_MS = 30 * 60 * 1000L
    }
}
