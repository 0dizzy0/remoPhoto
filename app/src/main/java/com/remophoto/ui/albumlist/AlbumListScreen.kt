package com.remophoto.ui.albumlist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
                    }
                },
                actions = {
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
                    // 分类管理入口
                    IconButton(
                        onClick = {
                            AppLogger.i(TAG, "点击分类管理按钮")
                            onCategoriesClick()
                        },
                        modifier = Modifier.semantics { contentDescription = "分类管理" }
                    ) {
                        Text("🏷️", modifier = Modifier.padding(4.dp))
                    }
                    // 仓库管理入口
                    IconButton(
                        onClick = {
                            AppLogger.i(TAG, "点击仓库管理按钮")
                            onRepositoryManagerClick()
                        },
                        modifier = Modifier.semantics { contentDescription = "仓库管理" }
                    ) {
                        Text("📁", modifier = Modifier.padding(4.dp))
                    }
                    // 设置入口
                    IconButton(
                        onClick = {
                            AppLogger.i(TAG, "点击设置按钮 (顶部栏入口)")
                            onSettingsClick()
                        },
                        modifier = Modifier.semantics { contentDescription = "设置" }
                    ) {
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
                EmptyStateView(
                    icon = "📷",
                    title = "暂无相册",
                    subtitle = "请先添加图片仓库",
                    actionLabel = "添加仓库",
                    onAction = onRepositoryManagerClick,
                    modifier = Modifier.padding(padding)
                )
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

                // 动画过渡：browsingAlbumId 变化时使用 AnimatedContent
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
                ) { _ ->
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
                            items(
                                displayAlbums,
                                key = { it.id },
                                contentType = { _ -> "album_grid_card" }
                            ) { album ->
                                AlbumCard(
                                    album = album,
                                    compact = true,
                                    onClick = {
                                        AppLogger.i(TAG, "点击相册卡片(网格): id=${album.id}, name=${album.name}, children=${album.children.size}")
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
                        val listState = rememberLazyListState()

                        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(
                                    displayAlbums,
                                    key = { it.id },
                                    contentType = { _ -> "album_list_card" }
                                ) { album ->
                                    AlbumCard(
                                        album = album,
                                        compact = false,
                                        onClick = {
                                            AppLogger.i(TAG, "点击相册卡片(列表): id=${album.id}, name=${album.name}, children=${album.children.size}")
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

                            // 快速滚动指示器（仅当相册数 > 20 时显示）
                            if (displayAlbums.size > 20) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                ) {
                                    ScrollPositionIndicator(
                                        listState = listState,
                                        itemCount = displayAlbums.size
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

private const val TAG = "AlbumList"

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
