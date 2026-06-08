package com.remophoto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.remophoto.ui.navigation.NavGraph
import com.remophoto.ui.theme.RemoPhotoTheme

/**
 * 单 Activity 入口
 *
 * 使用 Jetpack Compose 渲染全部 UI。
 * 通过 Compose Navigation 管理页面路由。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // SplashScreen API（在 setContentView 之前调用）
        installSplashScreen()

        super.onCreate(savedInstanceState)

        // 边到边显示（状态栏/导航栏透明）
        enableEdgeToEdge()

        setContent {
            RemoPhotoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavGraph()
                }
            }
        }
    }
}
