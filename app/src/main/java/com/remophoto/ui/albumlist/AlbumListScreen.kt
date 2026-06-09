package com.remophoto.ui.albumlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remophoto.domain.model.Album
import com.remophoto.ui.components.AlbumCard
import com.remophoto.ui.components.PageNavigator

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
    categoryName: String? = null
) {
    val albumTree by viewModel.albumTree.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isEmpty by viewModel.isEmpty.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val activeFilterCategoryName by viewModel.filterCategoryName.collectAsState()

    // 外部传入的分类筛选参数
    LaunchedEffect(categoryId) {
        if (categoryId != null && categoryName != null) {
            viewModel.loadAlbumsByCategory(categoryId, categoryName)
        } else {
            // 无筛选参数时清除旧筛选状态（防止切换页面后残留）
            viewModel.clearCategoryFilter()
        }
    }

    // 当前浏览路径的相册（用于子相册展开导航）
    var browsingAlbumId by remember { mutableStateOf<Long?>(null) }
    var browsingAlbumName by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = browsingAlbumName ?: activeFilterCategoryName ?: "remoPhoto"
                        )
                        if (activeFilterCategoryName != null) {
                            Text(
                                text = "筛选: $activeFilterCategoryName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (activeFilterCategoryName != null && browsingAlbumId == null) {
                        // 分类筛选模式：显示关闭筛选按钮
                        TextButton(onClick = {
                            viewModel.clearCategoryFilter()
                        }) {
                            Text("✕ 清除筛选")
                        }
                    } else if (browsingAlbumId != null) {
                        TextButton(onClick = {
                            browsingAlbumId = null
                            browsingAlbumName = null
                        }) {
                            Text("← 返回")
                        }
                    }
                },
                actions = {
                    // 布局切换按钮
                    IconButton(onClick = { viewModel.toggleLayoutMode() }) {
                        Icon(
                            imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = if (isGridView) "列表模式" else "网格模式"
                        )
                    }
                    // 分类管理入口
                    IconButton(onClick = onCategoriesClick) {
                        Text("🏷️", modifier = Modifier.padding(4.dp))
                    }
                    // 仓库管理入口
                    IconButton(onClick = onRepositoryManagerClick) {
                        Text("📁", modifier = Modifier.padding(4.dp))
                    }
                    // 设置入口
                    IconButton(onClick = onSettingsClick) {
                        Text("⚙️", modifier = Modifier.padding(4.dp))
                    }
                }
            )
        },
        bottomBar = {
            val totalPages = viewModel.totalPages()
            if (totalPages > 1) {
                PageNavigator(
                    currentPage = currentPage,
                    totalPages = totalPages,
                    onPageChange = { viewModel.goToPage(it) }
                )
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
            isEmpty -> {
                // 空状态
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "📷",
                            style = MaterialTheme.typography.displayLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "暂无相册",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "请先添加图片仓库",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                // 当前浏览层级：根级相册或子相册
                val displayAlbums = if (browsingAlbumId != null) {
                    // 查找指定相册的子相册
                    findAlbumById(albumTree, browsingAlbumId!!)?.children ?: emptyList()
                } else {
                    albumTree
                }

                if (displayAlbums.isEmpty() && browsingAlbumId != null) {
                    // 进入相册但没有子相册 → 跳转到图片网格
                    LaunchedEffect(browsingAlbumId) {
                        onAlbumClick(browsingAlbumId!!)
                    }
                }

                if (isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(displayAlbums, key = { it.id }) { album ->
                            AlbumCard(
                                album = album,
                                compact = true,
                                onClick = {
                                    if (album.children.isNotEmpty()) {
                                        browsingAlbumId = album.id
                                        browsingAlbumName = album.name
                                    } else {
                                        onAlbumClick(album.id)
                                    }
                                }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(displayAlbums, key = { it.id }) { album ->
                            AlbumCard(
                                album = album,
                                compact = false,
                                onClick = {
                                    if (album.children.isNotEmpty()) {
                                        browsingAlbumId = album.id
                                        browsingAlbumName = album.name
                                    } else {
                                        onAlbumClick(album.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
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
