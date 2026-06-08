package com.remophoto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.remophoto.data.repository.RepositoryManager
import com.remophoto.ui.navigation.NavGraph
import com.remophoto.ui.theme.RemoPhotoTheme
import com.remophoto.util.PermissionHelper

/**
 * 单 Activity 入口
 *
 * 使用 Jetpack Compose 渲染全部 UI。
 * 通过 Compose Navigation 管理页面路由。
 *
 * 初始化流程：
 * 1. SplashScreen API
 * 2. PermissionHelper 注册（SAF 目录选择器）
 * 3. RepositoryManager 创建（需要 PermissionHelper）
 * 4. 传递到 NavGraph → 各 Screen
 */
class MainActivity : ComponentActivity() {

    /** SAF 权限工具（在 onCreate 中初始化） */
    lateinit var permissionHelper: PermissionHelper
        private set

    /** 仓库管理器（在 onCreate 中初始化） */
    lateinit var repositoryManager: RepositoryManager
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        // SplashScreen API（在 setContentView 之前调用）
        installSplashScreen()

        super.onCreate(savedInstanceState)

        // 初始化 PermissionHelper
        permissionHelper = PermissionHelper(this)

        // 创建 RepositoryManager
        val app = application as RemoPhotoApp
        repositoryManager = app.dependencyContainer.createRepositoryManager(permissionHelper)

        // 边到边显示（状态栏/导航栏透明）
        enableEdgeToEdge()

        setContent {
            RemoPhotoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavGraph(
                        permissionHelper = permissionHelper,
                        repositoryManager = repositoryManager
                    )
                }
            }
        }
    }
}
