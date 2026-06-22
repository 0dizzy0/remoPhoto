package com.remophoto.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.remophoto.R
import com.remophoto.ui.albumlist.AlbumListScreen
import com.remophoto.ui.albumlist.AlbumListViewModel
import com.remophoto.ui.gallery.GalleryScreen
import com.remophoto.ui.viewer.FullScreenViewer
import com.remophoto.ui.settings.SettingsScreen
import com.remophoto.ui.settings.SettingsViewModel
import com.remophoto.ui.categories.CategoryListScreen
import com.remophoto.ui.albumlist.AlbumSettingsScreen
import com.remophoto.data.repository.RepositoryManager
import com.remophoto.ui.repository.RepositoryManagerScreen
import com.remophoto.ui.repository.RepositoryManagerViewModel
import com.remophoto.util.AppLogger
import com.remophoto.util.PermissionHelper

// ===== 路由定义 =====

/**
 * 页面路由定义
 */
sealed class Screen(val route: String) {

    /** 相册列表页（主页），可选分类筛选参数 */
    data object AlbumList : Screen("album_list?categoryId={categoryId}&categoryName={categoryName}") {
        const val ROUTE = "album_list?categoryId={categoryId}&categoryName={categoryName}"
        const val BASE_ROUTE = "album_list"
        fun createRoute(categoryId: Long? = null, categoryName: String? = null): String {
            return if (categoryId != null && categoryName != null)
                "album_list?categoryId=$categoryId&categoryName=$categoryName"
            else "album_list"
        }
    }

    /** 图片网格页 */
    data object Gallery : Screen("gallery/{albumId}") {
        const val ROUTE = "gallery/{albumId}"
        fun createRoute(albumId: Long) = "gallery/$albumId"
    }

    /** 全屏浏览页 */
    data object Viewer : Screen("viewer/{albumId}/{imageIndex}") {
        const val ROUTE = "viewer/{albumId}/{imageIndex}"
        fun createRoute(albumId: Long, index: Int) = "viewer/$albumId/$index"
    }

    /** 设置页 */
    data object Settings : Screen("settings") {
        const val ROUTE = "settings"
    }

    /** 仓库管理页 */
    data object RepositoryManager : Screen("repository_manager") {
        const val ROUTE = "repository_manager"
    }

    /** 分类管理页 */
    data object Categories : Screen("categories") {
        const val ROUTE = "categories"
    }

    /** 单相册设置页 */
    data object AlbumSettings : Screen("album_settings/{albumId}") {
        const val ROUTE = "album_settings/{albumId}"
        fun createRoute(albumId: Long) = "album_settings/$albumId"
    }
}

// ===== 导航图 =====

/**
 * 应用导航图（单 NavHost）
 *
 * 所有页面路由在此注册。
 * 页面间导航通过 NavController 管理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    permissionHelper: PermissionHelper? = null,
    repositoryManager: RepositoryManager? = null
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 路由变化日志
    LaunchedEffect(currentRoute) {
        AppLogger.i(TAG, "当前路由: $currentRoute")
    }

    // 判断是否需要显示底部导航栏（仅主页和设置页显示）
    // AlbumList 路由可能有查询参数，所以用 base route 匹配
    val showBottomBar = currentRoute?.startsWith(Screen.AlbumList.BASE_ROUTE) == true
            || currentRoute == Screen.Settings.ROUTE

    // 共享 ViewModel 实例（跨页面不需要共享的用独立实例）
    val albumListViewModel: AlbumListViewModel = viewModel()
    // 提前初始化设置状态，避免首次点击设置 Tab 时集中读取 DataStore/缓存统计。
    val settingsViewModel: SettingsViewModel = viewModel()

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = stringResource(R.string.album_list)) },
                        label = { Text(stringResource(R.string.album_list)) },
                        selected = currentRoute?.startsWith(Screen.AlbumList.BASE_ROUTE) == true,
                        onClick = {
                            if (currentRoute?.startsWith(Screen.AlbumList.BASE_ROUTE) != true) {
                                AppLogger.i(TAG, "📱 底部Tab点击: 相册列表 (from=$currentRoute)")
                                navController.navigate(Screen.AlbumList.BASE_ROUTE) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        inclusive = false
                                    }
                                    launchSingleTop = true
                                }
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings)) },
                        label = { Text(stringResource(R.string.settings)) },
                        selected = currentRoute == Screen.Settings.ROUTE,
                        onClick = {
                            if (currentRoute != Screen.Settings.ROUTE) {
                                AppLogger.i(TAG, "📱 底部Tab点击: 设置 (from=$currentRoute)")
                                navController.navigate(Screen.Settings.ROUTE) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        inclusive = false
                                    }
                                    launchSingleTop = true
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.AlbumList.ROUTE,
            modifier = Modifier.padding(innerPadding)
            // 不在 NavHost 层级设置 slide 动画 — 底部 Tab 切换应为即时切换
            // 深层导航（相册→网格→全屏）内部使用 AnimatedContent/AnimatedVisibility 提供过渡
        ) {
            // 相册列表页（主页），可选分类筛选
            composable(
                route = Screen.AlbumList.ROUTE,
                arguments = listOf(
                    navArgument("categoryId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("categoryName") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val categoryId = backStackEntry.arguments?.getString("categoryId")?.toLongOrNull()
                val categoryName = backStackEntry.arguments?.getString("categoryName")
                AlbumListScreen(
                    viewModel = albumListViewModel,
                    onAlbumClick = { albumId ->
                        AppLogger.i(TAG, "🧭 导航: 相册列表 → 图片网格 (albumId=$albumId)")
                        navController.navigate(Screen.Gallery.createRoute(albumId))
                    },
                    onSettingsClick = {
                        AppLogger.i(TAG, "🧭 导航: 相册列表 → 设置 (顶部栏入口)")
                        navController.navigate(Screen.Settings.ROUTE)
                    },
                    onRepositoryManagerClick = {
                        AppLogger.i(TAG, "🧭 导航: 相册列表 → 仓库管理")
                        navController.navigate(Screen.RepositoryManager.ROUTE)
                    },
                    onCategoriesClick = {
                        AppLogger.i(TAG, "🧭 导航: 相册列表 → 分类管理")
                        navController.navigate(Screen.Categories.ROUTE)
                    },
                    onAlbumSettingsClick = { albumId ->
                        AppLogger.i(TAG, "🧭 导航: 相册列表 → 相册设置 (albumId=$albumId)")
                        navController.navigate(Screen.AlbumSettings.createRoute(albumId))
                    },
                    categoryId = categoryId,
                    categoryName = categoryName
                )
            }

            // 图片网格页
            composable(
                route = Screen.Gallery.ROUTE,
                arguments = listOf(
                    navArgument("albumId") { type = NavType.LongType }
                )
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getLong("albumId") ?: 0L
                GalleryScreen(
                    albumId = albumId,
                    onImageClick = { index ->
                        AppLogger.i(TAG, "🧭 导航: 图片网格 → 全屏浏览 (albumId=$albumId, index=$index)")
                        navController.navigate(Screen.Viewer.createRoute(albumId, index))
                    },
                    onBack = {
                        AppLogger.i(TAG, "⬅ 返回: 图片网格 → 相册列表")
                        navController.popBackStack()
                    },
                    onAlbumSettingsClick = { id ->
                        AppLogger.i(TAG, "🧭 导航: 图片网格 → 相册设置 (albumId=$id)")
                        navController.navigate(Screen.AlbumSettings.createRoute(id))
                    }
                )
            }

            // 全屏浏览页
            composable(
                route = Screen.Viewer.ROUTE,
                arguments = listOf(
                    navArgument("albumId") { type = NavType.LongType },
                    navArgument("imageIndex") { type = NavType.IntType }
                )
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getLong("albumId") ?: 0L
                val imageIndex = backStackEntry.arguments?.getInt("imageIndex") ?: 0
                FullScreenViewer(
                    albumId = albumId,
                    imageIndex = imageIndex,
                    onBack = {
                        AppLogger.i(TAG, "⬅ 返回: 全屏浏览 → 图片网格")
                        navController.popBackStack()
                    }
                )
            }

            // 设置页
            composable(Screen.Settings.ROUTE) {
                SettingsScreen(
                    onBack = {
                        AppLogger.i(TAG, "⬅ 返回: 设置 → 上一页")
                        navController.popBackStack()
                    },
                    viewModel = settingsViewModel
                )
            }

            // 仓库管理页
            composable(Screen.RepositoryManager.ROUTE) {
                val repoViewModel: RepositoryManagerViewModel = viewModel()
                // 初始化 RepositoryManager（需要 Activity 绑定的 PermissionHelper）
                LaunchedEffect(repositoryManager) {
                    if (repositoryManager != null && repoViewModel.repositoryManager == null) {
                        repoViewModel.initialize(repositoryManager)
                    }
                }
                RepositoryManagerScreen(
                    viewModel = repoViewModel,
                    onBack = {
                        AppLogger.i(TAG, "⬅ 返回: 仓库管理 → 相册列表")
                        navController.popBackStack()
                    },
                    onScanComplete = {
                        // 扫描完成后刷新相册列表
                        AppLogger.i(TAG, "🔄 扫描完成 → 刷新相册列表")
                        albumListViewModel.refresh()
                    }
                )
            }

            // 分类管理页
            composable(Screen.Categories.ROUTE) {
                CategoryListScreen(
                    onBack = {
                        AppLogger.i(TAG, "⬅ 返回: 分类管理 → 相册列表")
                        navController.popBackStack()
                    },
                    onCategoryClick = { categoryId, categoryName ->
                        AppLogger.i(TAG, "🧭 导航: 分类管理 → 相册列表(筛选) (categoryId=$categoryId, name=$categoryName)")
                        // 点击分类：回到相册列表并按分类筛选
                        navController.navigate(
                            Screen.AlbumList.createRoute(categoryId, categoryName)
                        ) {
                            popUpTo(Screen.AlbumList.BASE_ROUTE) { inclusive = false }
                        }
                    }
                )
            }

            // 单相册设置页
            composable(
                route = Screen.AlbumSettings.ROUTE,
                arguments = listOf(
                    navArgument("albumId") { type = NavType.LongType }
                )
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getLong("albumId") ?: 0L
                AlbumSettingsScreen(
                    albumId = albumId,
                    onBack = {
                        AppLogger.i(TAG, "⬅ 返回: 相册设置 → 上一页")
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}

// ===== 占位页面（过渡期使用，后续 Phase 替换为实际实现） =====

/**
 * 占位页面 Composable
 *
 * 在中心显示标题和副标题，作为各页面的初始占位实现。
 * 后续 Phase 中将替换为具体的功能页面。
 */
private const val TAG = "NavGraph"

@Composable
fun PlaceholderScreen(title: String, subtitle: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$title\n\n$subtitle",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
    }
}
