package com.remophoto.ui.theme

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 主题模式
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

/**
 * remoPhoto 主题
 *
 * 支持三种模式：
 * - LIGHT：浅色主题
 * - DARK：深色主题（OLED 设备纯黑 #000000，LCD 设备深灰 #121212）
 * - SYSTEM：跟随系统设置
 *
 * @param themeMode 主题模式
 * @param content Composable 内容
 */
@Composable
fun RemoPhotoTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (darkTheme) {
        val bgColor = if (context.isOledDisplay()) {
            DarkBackgroundOled
        } else {
            DarkBackgroundLcd
        }
        darkColorScheme(
            primary = DarkPrimary,
            onPrimary = LightBackground,
            background = bgColor,
            surface = DarkSurface,
            onBackground = DarkOnBackground,
            onSurface = DarkOnBackground,
            onSurfaceVariant = DarkOnSurfaceVariant
        )
    } else {
        lightColorScheme(
            primary = LightPrimary,
            onPrimary = LightSurface,
            background = LightBackground,
            surface = LightSurface,
            onBackground = LightOnBackground,
            onSurface = LightOnBackground,
            onSurfaceVariant = LightOnSurfaceVariant
        )
    }

    // 设置状态栏和导航栏外观
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        content = content
    )
}

/**
 * 检测屏幕是否为 OLED 类型
 *
 * OLED 检测策略：
 * - API 31+：通过 Display.getHdrCapabilities() 辅助判断
 * - 其他情况：默认假定为 OLED（大多数现代 Android 中高端设备使用 OLED）
 * - 用户可在设置中手动调整
 */
fun Context.isOledDisplay(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return try {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            // 有 HDR 能力的通常是 OLED
            display?.hdrCapabilities != null
        } catch (e: Exception) {
            true // 安全默认值
        }
    }
    // API < 31：默认假定为 OLED（市面主流设备已是 OLED）
    return true
}
