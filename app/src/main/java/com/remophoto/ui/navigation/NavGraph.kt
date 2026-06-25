package com.remophoto.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.remophoto.ui.albumlist.AlbumListScreen
import com.remophoto.ui.albumlist.AlbumListViewModel
import com.remophoto.ui.gallery.GalleryScreen
import com.remophoto.ui.viewer.FullScreenViewer
import com.remophoto.ui.settings.SettingsScreen
import com.remophoto.ui.settings.SettingsViewModel
import com.remophoto.ui.categories.CategoryListScreen
import com.remophoto.ui.categories.CategoryViewModel
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
    data object AlbumList : Screen("album_list?categoryId={categoryId}&categoryName={categoryName}&repoId={repoId}&repoName={repoName}") {
        const val ROUTE = "album_list?categoryId={categoryId}&categoryName={categoryName}&repoId={repoId}&repoName={repoName}"
        const val BASE_ROUTE = "album_list"
        fun createRoute(
            categoryId: Long? = null,
            categoryName: String? = null,
            repoId: Long? = null,
            repoName: String? = null
        ): String {
            val params = buildList {
                if (categoryId != null && categoryName != null) {
                    add("categoryId=$categoryId")
                    add("categoryName=${Uri.encode(categoryName)}")
                }
                if (repoId != null && repoName != null) {
                    add("repoId=$repoId")
                    add("repoName=${Uri.encode(repoName)}")
                }
            }
            return if (params.isEmpty()) BASE_ROUTE else "$BASE_ROUTE?${params.joinToString("&")}"
        }
    }

    /** 图片网格页 */
    data object Gallery : Screen("gallery/{albumId}?returnRoute={returnRoute}") {
        const val ROUTE = "gallery/{albumId}?returnRoute={returnRoute}"
        fun createRoute(albumId: Long, returnRoute: String) =
            "gallery/$albumId?returnRoute=${Uri.encode(returnRoute)}"
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
    val showBottomBar = currentRoute?.startsWith(Screen.AlbumList.BASE_ROUTE) == true ||
            currentRoute == Screen.RepositoryManager.ROUTE ||
            currentRoute == Screen.Categories.ROUTE
    val isViewerRoute = currentRoute == Screen.Viewer.ROUTE

    // 共享 ViewModel 实例（跨页面不需要共享的用独立实例）
    val albumListViewModel: AlbumListViewModel = viewModel()
    val selectedRepoId by albumListViewModel.selectedRepoId.collectAsState()
    val selectedRepoName by albumListViewModel.selectedRepoName.collectAsState()
    val activeFilterCategoryName by albumListViewModel.filterCategoryName.collectAsState()
    val isAlbumLevel = selectedRepoId != null || activeFilterCategoryName != null
    // 提前初始化设置状态，避免首次点击设置 Tab 时集中读取 DataStore/缓存统计。
    val settingsViewModel: SettingsViewModel = viewModel()
    val repoViewModel: RepositoryManagerViewModel = viewModel()
    val categoryViewModel: CategoryViewModel = viewModel()
    val context = LocalContext.current

    LaunchedEffect(repositoryManager) {
        if (repositoryManager != null) {
            repoViewModel.initialize(repositoryManager)
        }
    }

    fun navigateTopLevel(route: String, resetAlbumListState: Boolean = true) {
        if (resetAlbumListState) {
            albumListViewModel.showRepositoryList()
        }
        val alreadyTarget = if (route == Screen.AlbumList.BASE_ROUTE) {
            currentRoute?.startsWith(Screen.AlbumList.BASE_ROUTE) == true && !isAlbumLevel
        } else {
            currentRoute == route
        }
        if (!alreadyTarget) {
            AppLogger.i(TAG, "Top-level switch: clear nested back stack, target=$route, from=$currentRoute")
            navController.navigate(route) {
                popUpTo(navController.graph.id) {
                    inclusive = false
                    saveState = false
                }
                launchSingleTop = true
                restoreState = false
            }
        }
    }

    val isTopLevelRoot = when {
        currentRoute?.startsWith(Screen.AlbumList.BASE_ROUTE) == true -> !isAlbumLevel
        currentRoute == Screen.RepositoryManager.ROUTE -> true
        currentRoute == Screen.Categories.ROUTE -> true
        else -> false
    }
    BackHandler(enabled = isTopLevelRoot) {
        AppLogger.i(TAG, "系统返回: 顶层页面退到后台 (route=$currentRoute)")
        context.findActivity()?.moveTaskToBack(true)
    }

    Scaffold(
        contentWindowInsets = if (isViewerRoute) {
            WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Outlined.Folder, contentDescription = "仓库列表") },
                        label = { Text("仓库列表") },
                        selected = currentRoute?.startsWith(Screen.AlbumList.BASE_ROUTE) == true && !isAlbumLevel,
                        onClick = {
                            AppLogger.i(TAG, "📱 底部Tab点击: 仓库列表 (from=$currentRoute)")
                            navigateTopLevel(Screen.AlbumList.BASE_ROUTE)
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Outlined.Source, contentDescription = "仓库管理") },
                        label = { Text("仓库管理") },
                        selected = currentRoute == Screen.RepositoryManager.ROUTE,
                        onClick = {
                            AppLogger.i(TAG, "📱 底部Tab点击: 仓库管理 (from=$currentRoute)")
                            navigateTopLevel(Screen.RepositoryManager.ROUTE)
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Outlined.Category, contentDescription = "分类管理") },
                        label = { Text("分类管理") },
                        selected = currentRoute == Screen.Categories.ROUTE,
                        onClick = {
                            AppLogger.i(TAG, "📱 底部Tab点击: 分类管理 (from=$currentRoute)")
                            navigateTopLevel(Screen.Categories.ROUTE)
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.AlbumList.ROUTE,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                routeEnterTransition(initialState.destination.route, targetState.destination.route)
            },
            exitTransition = {
                routeExitTransition(initialState.destination.route, targetState.destination.route)
            },
            popEnterTransition = {
                routeEnterTransition(initialState.destination.route, targetState.destination.route)
            },
            popExitTransition = {
                routeExitTransition(initialState.destination.route, targetState.destination.route)
            }
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
                    },
                    navArgument("repoId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("repoName") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val categoryId = backStackEntry.arguments?.getString("categoryId")?.toLongOrNull()
                val categoryName = backStackEntry.arguments?.getString("categoryName")?.let(Uri::decode)
                val repoId = backStackEntry.arguments?.getString("repoId")?.toLongOrNull()
                val repoName = backStackEntry.arguments?.getString("repoName")?.let(Uri::decode)
                AlbumListScreen(
                    viewModel = albumListViewModel,
                    onAlbumClick = { albumId ->
                        val returnRoute = albumListReturnRoute(
                            categoryId = categoryId,
                            categoryName = categoryName,
                            repoId = repoId ?: selectedRepoId,
                            repoName = repoName ?: selectedRepoName
                        )
                        AppLogger.i(TAG, "Navigate: album_list -> gallery (albumId=$albumId, returnRoute=$returnRoute)")
                        AppLogger.i(TAG, "🧭 导航: 相册列表 → 图片网格 (albumId=$albumId)")
                        navController.navigate(Screen.Gallery.createRoute(albumId, returnRoute))
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
                    categoryName = categoryName,
                    repoId = repoId,
                    repoName = repoName
                )
            }

            // 图片网格页
            composable(
                route = Screen.Gallery.ROUTE,
                arguments = listOf(
                    navArgument("albumId") { type = NavType.LongType },
                    navArgument("returnRoute") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getLong("albumId") ?: 0L
                val returnRoute = backStackEntry.arguments?.getString("returnRoute")
                    ?.let(Uri::decode)
                    ?: Screen.AlbumList.BASE_ROUTE
                GalleryScreen(
                    albumId = albumId,
                    onImageClick = { index ->
                        AppLogger.i(TAG, "🧭 导航: 图片网格 → 全屏浏览 (albumId=$albumId, index=$index)")
                        navController.navigate(Screen.Viewer.createRoute(albumId, index))
                    },
                    onBack = {
                        AppLogger.i(TAG, "⬅ 返回: 图片网格 → 相册列表")
                        AppLogger.i(TAG, "Return: gallery -> returnRoute=$returnRoute")
                        navigateToReturnRoute(navController, returnRoute)
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
                RepositoryManagerScreen(
                    viewModel = repoViewModel,
                    onBack = {
                        AppLogger.i(TAG, "系统返回: 仓库管理顶层退到后台")
                        context.findActivity()?.moveTaskToBack(true)
                    },
                    onSettingsClick = {
                        AppLogger.i(TAG, "🧭 导航: 仓库管理 → 设置")
                        navController.navigate(Screen.Settings.ROUTE)
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
                        AppLogger.i(TAG, "系统返回: 分类管理顶层退到后台")
                        context.findActivity()?.moveTaskToBack(true)
                    },
                    onSettingsClick = {
                        AppLogger.i(TAG, "🧭 导航: 分类管理 → 设置")
                        navController.navigate(Screen.Settings.ROUTE)
                    },
                    onCategoryClick = { categoryId, categoryName ->
                        AppLogger.i(TAG, "🧭 导航: 分类管理 → 相册列表(筛选) (categoryId=$categoryId, name=$categoryName)")
                        // 点击分类：回到相册列表并按分类筛选
                        navController.navigate(
                            Screen.AlbumList.createRoute(categoryId, categoryName)
                        ) {
                            popUpTo(Screen.AlbumList.ROUTE) { inclusive = false }
                        }
                    },
                    viewModel = categoryViewModel
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
private const val NAV_ANIMATION_MS = 220
private const val NAV_FADE_MS = 140

private fun albumListReturnRoute(
    categoryId: Long?,
    categoryName: String?,
    repoId: Long?,
    repoName: String?
): String = when {
    categoryId != null && categoryName != null -> {
        Screen.AlbumList.createRoute(categoryId = categoryId, categoryName = categoryName)
    }
    repoId != null && repoName != null -> {
        Screen.AlbumList.createRoute(repoId = repoId, repoName = repoName)
    }
    else -> Screen.AlbumList.BASE_ROUTE
}

private fun navigateToReturnRoute(navController: NavHostController, returnRoute: String) {
    val targetRoute = returnRoute.takeIf { it.startsWith(Screen.AlbumList.BASE_ROUTE) }
        ?: Screen.AlbumList.BASE_ROUTE
    AppLogger.i(TAG, "Strict returnRoute resolved: requested=$returnRoute, target=$targetRoute")
    navController.navigate(targetRoute) {
        popUpTo(Screen.AlbumList.ROUTE) { inclusive = false }
        launchSingleTop = true
    }
}

private fun routeEnterTransition(fromRoute: String?, toRoute: String?): EnterTransition {
    if (isTopLevelRoute(fromRoute) && isTopLevelRoute(toRoute)) {
        return EnterTransition.None
    }
    if (toRoute == Screen.Settings.ROUTE) {
        return fadeIn(animationSpec = tween(NAV_FADE_MS)) +
            slideInVertically(animationSpec = tween(NAV_ANIMATION_MS)) { -it / 3 } +
            slideInHorizontally(animationSpec = tween(NAV_ANIMATION_MS)) { it / 5 }
    }
    if (isDepthRoute(toRoute)) {
        return fadeIn(animationSpec = tween(NAV_FADE_MS)) +
            scaleIn(initialScale = 0.96f, animationSpec = tween(NAV_ANIMATION_MS))
    }
    val direction = navOrder(toRoute) - navOrder(fromRoute)
    return when {
        direction > 0 -> fadeIn(animationSpec = tween(NAV_FADE_MS)) +
            slideInHorizontally(animationSpec = tween(NAV_ANIMATION_MS)) { it / 3 }
        direction < 0 -> fadeIn(animationSpec = tween(NAV_FADE_MS)) +
            slideInHorizontally(animationSpec = tween(NAV_ANIMATION_MS)) { -it / 3 }
        else -> fadeIn(animationSpec = tween(NAV_FADE_MS))
    }
}

private fun routeExitTransition(fromRoute: String?, toRoute: String?): ExitTransition {
    if (isTopLevelRoute(fromRoute) && isTopLevelRoute(toRoute)) {
        return ExitTransition.None
    }
    if (fromRoute == Screen.Settings.ROUTE) {
        return fadeOut(animationSpec = tween(NAV_FADE_MS)) +
            slideOutVertically(animationSpec = tween(NAV_ANIMATION_MS)) { -it / 4 }
    }
    if (isDepthRoute(toRoute)) {
        return fadeOut(animationSpec = tween(NAV_FADE_MS)) +
            scaleOut(targetScale = 1.02f, animationSpec = tween(NAV_FADE_MS))
    }
    val direction = navOrder(toRoute) - navOrder(fromRoute)
    return when {
        direction > 0 -> fadeOut(animationSpec = tween(NAV_FADE_MS)) +
            slideOutHorizontally(animationSpec = tween(NAV_ANIMATION_MS)) { -it / 3 }
        direction < 0 -> fadeOut(animationSpec = tween(NAV_FADE_MS)) +
            slideOutHorizontally(animationSpec = tween(NAV_ANIMATION_MS)) { it / 3 }
        else -> fadeOut(animationSpec = tween(NAV_FADE_MS))
    }
}

private fun navOrder(route: String?): Int = when (route) {
    Screen.AlbumList.ROUTE, Screen.AlbumList.BASE_ROUTE -> 0
    Screen.RepositoryManager.ROUTE -> 1
    Screen.Categories.ROUTE -> 2
    else -> 0
}

private fun isDepthRoute(route: String?): Boolean = route == Screen.Gallery.ROUTE ||
    route == Screen.Viewer.ROUTE ||
    route == Screen.AlbumSettings.ROUTE

private fun isTopLevelRoute(route: String?): Boolean =
    route?.startsWith(Screen.AlbumList.BASE_ROUTE) == true ||
        route == Screen.RepositoryManager.ROUTE ||
        route == Screen.Categories.ROUTE

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

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
