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
import androidx.compose.ui.graphics.Color
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
 * 深色背景类型
 *
 * - AUTO：自动检测设备屏幕类型（OLED → #000000，LCD → #121212）
 * - OLED：强制使用纯黑背景 #000000
 * - LCD：强制使用深灰背景 #121212
 */
enum class DarkModeType {
    AUTO,
    OLED,
    LCD
}

/**
 * remoPhoto 主题
 *
 * 支持：
 * - LIGHT：浅色主题
 * - DARK：深色主题（OLED/LCD 自适应或手动指定）
 * - SYSTEM：跟随系统设置
 * - 高对比度模式：提高文字-背景对比度
 *
 * @param themeMode 主题模式
 * @param darkModeType 深色背景类型（仅深色模式时生效）
 * @param highContrast 是否启用高对比度
 * @param content Composable 内容
 */
@Composable
fun RemoPhotoTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    darkModeType: DarkModeType = DarkModeType.AUTO,
    highContrast: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (darkTheme) {
        // 深色背景色选择
        val bgColor = when (darkModeType) {
            DarkModeType.OLED -> DarkBackgroundOled
            DarkModeType.LCD -> DarkBackgroundLcd
            DarkModeType.AUTO -> if (context.isOledDisplay()) DarkBackgroundOled else DarkBackgroundLcd
        }

        val onBg = if (highContrast) HighContrastDarkOnBackground else DarkOnBackground
        val onSurf = if (highContrast) HighContrastDarkOnSurface else DarkOnSurface
        val onSurfVariant = if (highContrast) HighContrastDarkOnSurfaceVariant else DarkOnSurfaceVariant

        darkColorScheme(
            primary = DarkPrimary,
            onPrimary = DarkOnPrimary,
            primaryContainer = DarkPrimaryContainer,
            onPrimaryContainer = DarkOnPrimaryContainer,
            secondary = DarkSecondary,
            onSecondary = DarkOnSecondary,
            secondaryContainer = DarkSecondaryContainer,
            onSecondaryContainer = DarkOnSecondaryContainer,
            tertiary = DarkTertiary,
            onTertiary = DarkOnTertiary,
            tertiaryContainer = DarkTertiaryContainer,
            onTertiaryContainer = DarkOnTertiaryContainer,
            error = DarkError,
            onError = DarkOnError,
            errorContainer = DarkErrorContainer,
            onErrorContainer = DarkOnErrorContainer,
            background = bgColor,
            onBackground = onBg,
            surface = DarkSurface,
            onSurface = onSurf,
            surfaceVariant = DarkSurfaceVariant,
            onSurfaceVariant = onSurfVariant,
            outline = DarkOutline,
            outlineVariant = DarkOutlineVariant,
            inverseSurface = DarkInverseSurface,
            inverseOnSurface = DarkInverseOnSurface,
            inversePrimary = DarkInversePrimary
        )
    } else {
        val onBg = if (highContrast) HighContrastLightOnBackground else LightOnBackground
        val onSurf = if (highContrast) HighContrastLightOnSurface else LightOnSurface
        val onSurfVariant = if (highContrast) HighContrastLightOnSurfaceVariant else LightOnSurfaceVariant

        lightColorScheme(
            primary = LightPrimary,
            onPrimary = LightOnPrimary,
            primaryContainer = LightPrimaryContainer,
            onPrimaryContainer = LightOnPrimaryContainer,
            secondary = LightSecondary,
            onSecondary = LightOnSecondary,
            secondaryContainer = LightSecondaryContainer,
            onSecondaryContainer = LightOnSecondaryContainer,
            tertiary = LightTertiary,
            onTertiary = LightOnTertiary,
            tertiaryContainer = LightTertiaryContainer,
            onTertiaryContainer = LightOnTertiaryContainer,
            error = LightError,
            onError = LightOnError,
            errorContainer = LightErrorContainer,
            onErrorContainer = LightOnErrorContainer,
            background = LightBackground,
            onBackground = onBg,
            surface = LightSurface,
            onSurface = onSurf,
            surfaceVariant = LightSurfaceVariant,
            onSurfaceVariant = onSurfVariant,
            outline = LightOutline,
            outlineVariant = LightOutlineVariant,
            inverseSurface = LightInverseSurface,
            inverseOnSurface = LightInverseOnSurface,
            inversePrimary = LightInversePrimary
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
        typography = AppTypography,
        content = content
    )
}

/**
 * 检测屏幕是否为 OLED 类型
 *
 * OLED 检测策略：
 * - API 31+：通过 Display.getHdrCapabilities() 辅助判断（有 HDR 能力通常是 OLED）
 * - 其他情况：默认假定为 OLED（大多数现代 Android 中高端设备使用 OLED）
 * - 用户可在设置中手动调整为 LCD 模式
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
