package com.remophoto.ui.albumlist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.ui.components.AddRemoteRepoDialog
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.remophoto.data.local.entity.CategoryEntity
import com.remophoto.data.local.entity.RepositoryEntity
import com.remophoto.domain.model.Album
import com.remophoto.ui.components.AlbumCard
import com.remophoto.ui.components.EmptyStateView
import com.remophoto.ui.components.PageNavigator
import com.remophoto.util.AppLogger

/**
 * 相册列表页面
 *
 * 显示所有相册的多层级树形列表：
 * - 支持双列网格 / 单列列表切换
 * - 子相册左侧缩进显示
 * - 面包屑导航（最大深度 3 层）
 * - 分页导航
 * - 空状态提示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumListScreen(
    viewModel: AlbumListViewModel,
    onAlbumClick: (Long) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onRepositoryManagerClick: () -> Unit = {},
    onCategoriesClick: () -> Unit = {},
    onAlbumSettingsClick: (Long) -> Unit = {},
    categoryId: Long? = null,
    categoryName: String? = null,
    repoId: Long? = null,
    repoName: String? = null
) {
    val albumTree by viewModel.albumTree.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isEmpty by viewModel.isEmpty.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val activeFilterCategoryName by viewModel.filterCategoryName.collectAsState()
    val activeFilterCategoryId by viewModel.filterCategoryId.collectAsState()
    val pagedAlbums by viewModel.pagedAlbums.collectAsState()
    val selectedRepoId by viewModel.selectedRepoId.collectAsState()
    val selectedRepoName by viewModel.selectedRepoName.collectAsState()
    val repoList by viewModel.repoList.collectAsState()
    val isRefreshingRemote by viewModel.isRefreshingRemote.collectAsState()

    // 多选状态
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedAlbumIds by viewModel.selectedAlbumIds.collectAsState()
    val allCategories by viewModel.allCategories.collectAsState()

    // 分类选择器弹窗
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showRemoveFromCategoryConfirm by remember { mutableStateOf(false) }

    // Phase 4: 添加远程仓库对话框
    var showAddRemoteDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 外部传入的分类筛选参数
    LaunchedEffect(categoryId, categoryName, repoId, repoName) {
        when {
            categoryId != null && categoryName != null -> {
                AppLogger.i(TAG, "Apply album_list route: categoryId=$categoryId, categoryName=$categoryName")
                if (activeFilterCategoryId != categoryId) {
                    viewModel.loadAlbumsByCategory(categoryId, categoryName)
                } else {
                    AppLogger.i(TAG, "Keep album_list state: categoryId=$categoryId, page=$currentPage")
                }
            }
            repoId != null && repoName != null -> {
                AppLogger.i(TAG, "Apply album_list route: repoId=$repoId, repoName=$repoName")
                if (selectedRepoId != repoId) {
                    viewModel.selectRepo(repoId, repoName)
                } else {
                    AppLogger.i(TAG, "Keep album_list state: repoId=$repoId, page=$currentPage")
                }
            }
            else -> {
                AppLogger.i(TAG, "Apply album_list route: repository root")
                // 无筛选参数时清除旧筛选状态（防止切换页面后残留）
                viewModel.showRepositoryList()
            }
        }
    }

    // 当前浏览路径的相册（用于子相册展开导航）
    var browsingAlbumId by remember { mutableStateOf<Long?>(null) }
    var browsingAlbumName by remember { mutableStateOf<String?>(null) }

    // 判断当前显示模式
    val showRepoLevel = selectedRepoId == null && activeFilterCategoryName == null
    val showAlbumLevel = !showRepoLevel

    // 系统返回键：逐级返回（子相册 → 父相册 → 仓库列表 → 退出）
    BackHandler(enabled = selectionMode || browsingAlbumId != null || selectedRepoId != null || activeFilterCategoryName != null) {
        when {
            selectionMode -> viewModel.exitSelectionMode()
            browsingAlbumId != null -> {
                browsingAlbumId = null
                browsingAlbumName = null
            }
            selectedRepoId != null -> {
                browsingAlbumId = null
                browsingAlbumName = null
                viewModel.clearRepoSelection()
            }
            activeFilterCategoryName != null -> viewModel.clearCategoryFilter()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionMode) {
                // 多选模式 TopBar
                TopAppBar(
                    title = { Text("已选 ${selectedAlbumIds.size} 项") },
                    navigationIcon = {
                        TextButton(onClick = {
                            AppLogger.i(TAG, "点击取消: 退出多选模式")
                            viewModel.exitSelectionMode()
                        }) {
                            Text("取消")
                        }
                    },
                    actions = {
                        if (selectedAlbumIds.isNotEmpty()) {
                            if (activeFilterCategoryName != null) {
                                TextButton(onClick = {
                                    AppLogger.i(
                                        TAG,
                                        "点击从分类删除: category=$activeFilterCategoryName, 已选=${selectedAlbumIds.size}项"
                                    )
                                    showRemoveFromCategoryConfirm = true
                                }) {
                                    Text("删除", color = MaterialTheme.colorScheme.error)
                                }
                            } else {
                                TextButton(onClick = {
                                    AppLogger.i(TAG, "点击添加到分类: 已选=${selectedAlbumIds.size}项")
                                    viewModel.loadAllCategories()
                                    showCategoryPicker = true
                                }) {
                                    Text("添加到分类")
                                }
                            }
                        }
                    }
                )
            } else {
                // 正常模式 TopBar
                TopAppBar(
                    title = {
                        // 分类筛选时显示分类名；仓库列表时显示应用名；进入仓库后不显示（空间留给按钮）
                        activeFilterCategoryName?.let { name ->
                            Text(text = name, style = MaterialTheme.typography.titleMedium)
                        } ?: run {
                            if (selectedRepoId == null && browsingAlbumId == null) {
                                Text(text = "remoPhoto", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    },
                    navigationIcon = {
                        if (activeFilterCategoryName != null && browsingAlbumId == null) {
                            // 分类筛选模式：显示关闭筛选按钮
                            TextButton(onClick = {
                                AppLogger.i(TAG, "点击清除筛选按钮")
                                viewModel.clearCategoryFilter()
                            }) {
                                Text("✕ 清除筛选")
                            }
                        } else if (browsingAlbumId != null) {
                            TextButton(onClick = {
                                AppLogger.i(TAG, "点击返回按钮: 子相册 → 父相册")
                                browsingAlbumId = null
                                browsingAlbumName = null
                            }) {
                                Text("← 返回")
                            }
                        } else if (selectedRepoId != null) {
                            TextButton(onClick = {
                                AppLogger.i(TAG, "点击返回按钮: 相册 → 仓库列表")
                                browsingAlbumId = null
                                browsingAlbumName = null
                                viewModel.clearRepoSelection()
                            }) {
                                Text("← 仓库列表")
                            }
                        }
                    },
                    actions = {
                        if (showAlbumLevel && browsingAlbumId == null) {
                            val selectedRepo = repoList.find { it.id == selectedRepoId }
                            if (selectedRepo?.remoteConnectionId != null) {
                                IconButton(
                                    onClick = viewModel::refreshSelectedRemoteRepository,
                                    enabled = !isRefreshingRemote
                                ) {
                                    if (isRefreshingRemote) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = "刷新远程相册缓存")
                                    }
                                }
                            }
                            IconButton(onClick = { showSearchDialog = true }) {
                                Icon(Icons.Default.Search, contentDescription = "查找相册")
                            }
                        }
                        // 布局切换按钮
                        IconButton(onClick = {
                            AppLogger.i(TAG, "点击布局切换 (当前=${if (isGridView) "网格" else "列表"})")
                            viewModel.toggleLayoutMode()
                        }) {
                            Icon(
                                imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                contentDescription = if (isGridView) "列表模式" else "网格模式"
                            )
                        }
                        IconButton(
                            onClick = {
                                AppLogger.i(TAG, "点击设置按钮 (顶部栏入口)")
                                onSettingsClick()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = "设置",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            // 仅在相册根级（非仓库列表、非子相册浏览、非空）时显示分页
            if (showAlbumLevel && browsingAlbumId == null) {
                if (totalPages > 1) {
                    PageNavigator(
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageChange = { viewModel.goToPage(it) }
                    )
                }
            }
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            // 仓库层必须优先于全局相册空状态，否则零本地仓库时远程添加入口不可见。
            !showRepoLevel && isEmpty -> {
                if (activeFilterCategoryName != null) {
                    // 分类筛选结果为空
                    EmptyStateView(
                        icon = "🏷️",
                        title = "暂无相册",
                        subtitle = "请在主界面添加相册至此分类",
                        modifier = Modifier.padding(padding)
                    )
                } else {
                    // 全局无相册
                    EmptyStateView(
                        icon = "📷",
                        title = "暂无相册",
                        subtitle = "请先添加图片仓库",
                        actionLabel = "添加仓库",
                        onAction = onRepositoryManagerClick,
                        modifier = Modifier.padding(padding)
                    )
                }
            }
            else -> {
                AnimatedContent(
                    targetState = showRepoLevel,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(180)) +
                            scaleIn(initialScale = 0.96f, animationSpec = tween(220)))
                            .togetherWith(
                                fadeOut(animationSpec = tween(120)) +
                                    scaleOut(targetScale = 1.02f, animationSpec = tween(120))
                            )
                    },
                    label = "repo_album_level_switch"
                ) { repoLevel ->
                if (repoLevel) {
                    // ===== 仓库列表视图 =====
                    val localRepos = repoList.filter { it.remoteConnectionId == null }
                    val remoteRepos = repoList.filter { it.remoteConnectionId != null }

                    // 统一的 LazyColumn（始终显示，确保远程仓库添加入口可见）
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // ===== 本地仓库 Section =====
                        item { SectionHeader("本地仓库") }
                        if (localRepos.isNotEmpty()) {
                            if (isGridView) {
                                items(
                                    localRepos.chunked(2),
                                    key = { it.firstOrNull()?.id ?: 0 }
                                ) { row ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        row.forEach { repo ->
                                            Box(modifier = Modifier.weight(1f)) {
                                                RepoCard(repo = repo, compact = true, onClick = {
                                                    browsingAlbumId = null; browsingAlbumName = null
                                                    viewModel.selectRepo(repo.id, repo.name)
                                                })
                                            }
                                        }
                                        // 单数补齐
                                        if (row.size == 1) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            } else {
                                items(localRepos, key = { it.id }) { repo ->
                                    RepoCard(repo = repo, compact = false, onClick = {
                                        browsingAlbumId = null; browsingAlbumName = null
                                        viewModel.selectRepo(repo.id, repo.name)
                                    })
                                }
                            }
                        } else {
                            // 无本地仓库时显示提示 + 快速添加入口
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "暂无本地仓库",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(onClick = onRepositoryManagerClick) {
                                        Text("＋ 添加本地仓库")
                                    }
                                }
                            }
                        }

                        // ===== 远程仓库 Section（始终显示） =====
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "远程仓库",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                TextButton(onClick = {
                                    AppLogger.i(TAG, "点击添加远程仓库")
                                    showAddRemoteDialog = true
                                }) {
                                    Text("＋ 添加")
                                }
                            }
                        }
                        if (remoteRepos.isEmpty()) {
                            item {
                                Text(
                                    "点击上方「＋ 添加」连接局域网内的 remoPhoto 设备",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        } else if (isGridView) {
                            items(
                                remoteRepos.chunked(2),
                                key = { it.firstOrNull()?.id ?: 0 }
                            ) { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    row.forEach { repo ->
                                        Box(modifier = Modifier.weight(1f)) {
                                            RemoteRepoCard(repo = repo, viewModel = viewModel, compact = true, onClick = {
                                                browsingAlbumId = null; browsingAlbumName = null
                                                viewModel.selectRepo(repo.id, repo.name)
                                            })
                                        }
                                    }
                                    if (row.size == 1) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        } else {
                            items(remoteRepos, key = { it.id }) { repo ->
                                RemoteRepoCard(repo = repo, viewModel = viewModel, compact = false, onClick = {
                                    browsingAlbumId = null; browsingAlbumName = null
                                    viewModel.selectRepo(repo.id, repo.name)
                                })
                            }
                        }
                    }
                } else {
                    // ===== 相册列表视图 =====
                    val gridState = rememberLazyGridState()
                    val listState = rememberLazyListState()

                    LaunchedEffect(currentPage) {
                        if (browsingAlbumId == null) {
                            gridState.scrollToItem(0)
                            listState.scrollToItem(0)
                            AppLogger.i(TAG, "分页后回到列表顶部: page=$currentPage")
                        }
                    }

                    // 根级用分页数据，子相册用全量子相册
                    val displayAlbums = if (browsingAlbumId != null) {
                        remember(albumTree, browsingAlbumId) {
                            findAlbumById(albumTree, browsingAlbumId!!)?.children ?: emptyList()
                        }
                    } else {
                        pagedAlbums
                    }

                    if (displayAlbums.isEmpty() && browsingAlbumId != null) {
                        // 进入相册但没有子相册 → 跳转到图片网格
                        LaunchedEffect(browsingAlbumId) {
                            onAlbumClick(browsingAlbumId!!)
                        }
                    }

                    // 动画过渡
                    val contentKey = browsingAlbumId ?: 0L

                    AnimatedContent(
                        targetState = contentKey,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(200)) +
                                expandVertically(animationSpec = tween(250)))
                                .togetherWith(
                                    fadeOut(animationSpec = tween(150)) +
                                        shrinkVertically(animationSpec = tween(200))
                                )
                        },
                        label = "album_content"
                    ) { animatedContentKey ->
                        val animatedDisplayAlbums = if (animatedContentKey != 0L) {
                            findAlbumById(albumTree, animatedContentKey)?.children ?: emptyList()
                        } else {
                            pagedAlbums
                        }
                        if (isGridView) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                state = gridState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(padding),
                                contentPadding = PaddingValues(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(
                                    animatedDisplayAlbums,
                                    key = { it.id },
                                    contentType = { _ -> "album_grid_card" }
                                ) { album ->
                                    AlbumCard(
                                        album = album,
                                        compact = true,
                                        selected = album.id in selectedAlbumIds,
                                        selectionMode = selectionMode,
                                        onClick = {
                                            if (selectionMode) {
                                                viewModel.toggleSelection(album.id)
                                            } else {
                                                AppLogger.i(TAG, "点击相册卡片(网格): id=${album.id}, name=${album.name}, children=${album.children.size}")
                                                if (album.children.isNotEmpty()) {
                                                    browsingAlbumId = album.id
                                                    browsingAlbumName = album.name
                                                } else {
                                                    onAlbumClick(album.id)
                                                }
                                            }
                                        },
                                        onLongClick = { viewModel.enterSelectionMode(album.id) }
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(
                                        animatedDisplayAlbums,
                                        key = { it.id },
                                        contentType = { _ -> "album_list_card" }
                                    ) { album ->
                                        AlbumCard(
                                            album = album,
                                            compact = false,
                                            selected = album.id in selectedAlbumIds,
                                            selectionMode = selectionMode,
                                            onClick = {
                                                if (selectionMode) {
                                                    viewModel.toggleSelection(album.id)
                                                } else {
                                                    AppLogger.i(TAG, "点击相册卡片(列表): id=${album.id}, name=${album.name}, children=${album.children.size}")
                                                    if (album.children.isNotEmpty()) {
                                                        browsingAlbumId = album.id
                                                        browsingAlbumName = album.name
                                                    } else {
                                                        onAlbumClick(album.id)
                                                    }
                                                }
                                            },
                                            onLongClick = { viewModel.enterSelectionMode(album.id) }
                                        )
                                    }
                                }

                                // 快速滚动指示器（仅当相册数 > 20 时显示）
                                if (animatedDisplayAlbums.size > 20) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .fillMaxHeight()
                                    ) {
                                        ScrollPositionIndicator(
                                            listState = listState,
                                            itemCount = animatedDisplayAlbums.size
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }

    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("查找相册") },
            text = {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("相册名称") },
                    supportingText = { Text("按名称模糊查找，只跳转到所在页") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = searchQuery.isNotBlank(),
                    onClick = {
                        val found = viewModel.locateAlbumByName(searchQuery)
                        showSearchDialog = false
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                found?.let { "已定位到“${it.name}”所在页" }
                                    ?: "未找到名称包含“${searchQuery.trim()}”的相册"
                            )
                        }
                    }
                ) { Text("查找") }
            },
            dismissButton = {
                TextButton(onClick = { showSearchDialog = false }) { Text("取消") }
            }
        )
    }

    // Phase 4: 添加远程仓库对话框
    if (showAddRemoteDialog) {
        AddRemoteRepoDialog(
            onDismiss = { showAddRemoteDialog = false },
            onRepoAdded = { viewModel.refresh() }
        )
    }

    // 分类选择器弹窗
    if (showCategoryPicker) {
        AlertDialog(
            onDismissRequest = { showCategoryPicker = false },
            title = { Text("选择分类") },
            text = {
                if (allCategories.isEmpty()) {
                    Text(
                        text = "暂无分类，请先在分类管理中创建",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn {
                        items(allCategories, key = { it.id }) { category ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        AppLogger.i(TAG, "分类选择器: 选择分类 id=${category.id}, name=${category.name}")
                                        viewModel.addSelectedToCategory(category.id)
                                        showCategoryPicker = false
                                    }
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 颜色圆点
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(category.color))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCategoryPicker = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showRemoveFromCategoryConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveFromCategoryConfirm = false },
            title = { Text("删除相册分类") },
            text = {
                Text(
                    "确定要从「${activeFilterCategoryName ?: "当前分类"}」中删除选中的 ${selectedAlbumIds.size} 个相册吗？\n\n" +
                        "此操作只会移除分类关联，不会删除相册或图片文件。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeSelectedFromActiveCategory()
                        showRemoveFromCategoryConfirm = false
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveFromCategoryConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

private const val TAG = "AlbumList"

/**
 * 仓库卡片组件（用于仓库列表视图）
 *
 * 网格模式：大卡片，含文件夹图标和统计信息
 * 列表模式：紧凑横排布局
 */
@Composable
private fun RepoCard(
    repo: RepositoryEntity,
    compact: Boolean,
    onClick: () -> Unit
) {
    val lastScanText = if (repo.lastScanTime == 0L) "未扫描"
    else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(repo.lastScanTime))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        if (compact) {
            // 双列网格：纵向布局，居中显示
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "📁",
                    style = MaterialTheme.typography.displaySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = repo.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${repo.imageCount} 张图片",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = lastScanText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        } else {
            // 单列列表：横排布局
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📁",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = repo.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = "${repo.imageCount} 张图片",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = lastScanText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = "›",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Phase 4: 远程仓库卡片组件（含连接状态指示）
 */
@Composable
private fun RemoteRepoCard(
    repo: RepositoryEntity,
    viewModel: AlbumListViewModel,
    compact: Boolean,
    onClick: () -> Unit
) {
    val status = viewModel.getRemoteStatus(repo)
    val statusColor = when (status) {
        ConnectionStatus.CONNECTED -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
        ConnectionStatus.DISCONNECTED -> androidx.compose.ui.graphics.Color(0xFF9E9E9E)
        ConnectionStatus.ERROR -> androidx.compose.ui.graphics.Color(0xFFF44336)
    }
    val statusText = when (status) {
        ConnectionStatus.CONNECTED -> "已连接"
        ConnectionStatus.DISCONNECTED -> "离线"
        ConnectionStatus.ERROR -> "错误"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        if (compact) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "🌐", style = MaterialTheme.typography.displaySmall)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = repo.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${repo.imageCount} 张图片 · $statusText",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "🌐", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(end = 12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = repo.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${repo.imageCount} 张图片 · $statusText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(text = "›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Section 标题（本地仓库 / 远程仓库）
 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 8.dp)
    )
}

/**
 * 递归查找相册
 */
private fun findAlbumById(albums: List<Album>, id: Long): Album? {
    for (album in albums) {
        if (album.id == id) return album
        val found = findAlbumById(album.children, id)
        if (found != null) return found
    }
    return null
}

/**
 * 快速滚动位置指示器
 *
 * 在列表右侧显示一条半透明竖线，指示当前滚动进度。
 * 滚动时可见，停止后 1.5 秒自动淡出。
 *
 * 简化实现：仅显示滚动进度条轨道 + 滑块位置。
 */
@Composable
private fun ScrollPositionIndicator(
    listState: androidx.compose.foundation.lazy.LazyListState,
    itemCount: Int
) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            isVisible = true
        } else {
            delay(1500L)
            isVisible = false
        }
    }

    val scrollProgress by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 1) {
                listState.firstVisibleItemIndex.toFloat() / (total - 1).toFloat()
            } else 0f
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (isVisible || listState.isScrollInProgress) 0.5f else 0f,
        animationSpec = tween(300),
        label = "scroll_alpha"
    )

    // 使用 Box + Modifier.layout 实现滑块位置
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 16.dp, horizontal = 2.dp)
            .width(3.dp)
            .alpha(alpha)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.12f))
    ) {
        // 滑块 — 使用 Modifier.layout 精确控制 y 偏移
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(
                    fraction = (1f / itemCount.coerceAtLeast(1)).coerceIn(0.04f, 0.12f)
                )
                .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val maxY = (constraints.maxHeight - placeable.height).coerceAtLeast(0)
                    val yOffset = (scrollProgress * maxY).toInt()
                    layout(placeable.width, constraints.maxHeight) {
                        placeable.placeRelative(0, yOffset)
                    }
                }
        )
    }
}
