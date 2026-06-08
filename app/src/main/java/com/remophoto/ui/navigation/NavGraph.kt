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
import com.remophoto.R
import com.remophoto.ui.albumlist.AlbumListScreen
import com.remophoto.ui.albumlist.AlbumListViewModel
import com.remophoto.ui.gallery.GalleryScreen
import com.remophoto.ui.viewer.FullScreenViewer
import com.remophoto.ui.settings.SettingsScreen
import com.remophoto.data.repository.RepositoryManager
import com.remophoto.ui.repository.RepositoryManagerScreen
import com.remophoto.ui.repository.RepositoryManagerViewModel
import com.remophoto.util.PermissionHelper

// ===== 路由定义 =====

/**
 * 页面路由定义
 */
sealed class Screen(val route: String) {

    /** 相册列表页（主页） */
    data object AlbumList : Screen("album_list") {
        const val ROUTE = "album_list"
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

    // 判断是否需要显示底部导航栏（仅主页和设置页显示）
    val showBottomBar = currentRoute in listOf(
        Screen.AlbumList.ROUTE,
        Screen.Settings.ROUTE
    )

    // 共享 ViewModel 实例（跨页面不需要共享的用独立实例）
    val albumListViewModel: AlbumListViewModel = viewModel()

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                        label = { Text(stringResource(R.string.album_list)) },
                        selected = currentRoute == Screen.AlbumList.ROUTE,
                        onClick = {
                            if (currentRoute != Screen.AlbumList.ROUTE) {
                                navController.navigate(Screen.AlbumList.ROUTE) {
                                    popUpTo(Screen.AlbumList.ROUTE) { inclusive = true }
                                }
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.settings)) },
                        selected = currentRoute == Screen.Settings.ROUTE,
                        onClick = {
                            if (currentRoute != Screen.Settings.ROUTE) {
                                navController.navigate(Screen.Settings.ROUTE) {
                                    popUpTo(Screen.AlbumList.ROUTE)
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
        ) {
            // 相册列表页（主页）
            composable(Screen.AlbumList.ROUTE) {
                AlbumListScreen(
                    viewModel = albumListViewModel,
                    onAlbumClick = { albumId ->
                        navController.navigate(Screen.Gallery.createRoute(albumId))
                    },
                    onSettingsClick = {
                        navController.navigate(Screen.Settings.ROUTE)
                    },
                    onRepositoryManagerClick = {
                        navController.navigate(Screen.RepositoryManager.ROUTE)
                    }
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
                        navController.navigate(Screen.Viewer.createRoute(albumId, index))
                    },
                    onBack = { navController.popBackStack() }
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
                    onBack = { navController.popBackStack() }
                )
            }

            // 设置页
            composable(Screen.Settings.ROUTE) {
                SettingsScreen(
                    onBack = { navController.popBackStack() }
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
                    onBack = { navController.popBackStack() },
                    onScanComplete = {
                        // 扫描完成后刷新相册列表
                        albumListViewModel.refresh()
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
