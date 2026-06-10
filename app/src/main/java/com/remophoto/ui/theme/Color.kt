package com.remophoto.ui.theme

import androidx.compose.ui.graphics.Color

// ===== 浅色主题色板 =====

/** 浅色 Primary — 主色调 */
val LightPrimary = Color(0xFF1565C0)
/** 浅色 On Primary — 主色上的文字 */
val LightOnPrimary = Color(0xFFFFFFFF)
/** 浅色 Primary Container — 主色容器 */
val LightPrimaryContainer = Color(0xFFD1E4FF)
/** 浅色 On Primary Container — 主色容器上的文字 */
val LightOnPrimaryContainer = Color(0xFF001D36)

/** 浅色 Secondary */
val LightSecondary = Color(0xFF535F70)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFD7E3F7)
val LightOnSecondaryContainer = Color(0xFF101C2B)

/** 浅色 Tertiary */
val LightTertiary = Color(0xFF6B5778)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFF2DAFF)
val LightOnTertiaryContainer = Color(0xFF251432)

/** 浅色 Error */
val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

/** 浅色 Background / Surface */
val LightBackground = Color(0xFFFAFAFA)
val LightOnBackground = Color(0xFF1A1C1E)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF1A1C1E)
val LightSurfaceVariant = Color(0xFFE0E2EC)
val LightOnSurfaceVariant = Color(0xFF44474E)
val LightOutline = Color(0xFF74777F)
val LightOutlineVariant = Color(0xFFC4C6D0)

/** 浅色 Inverse */
val LightInverseSurface = Color(0xFF2F3033)
val LightInverseOnSurface = Color(0xFFF1F0F4)
val LightInversePrimary = Color(0xFFA0CAFD)

// ===== 深色主题色板（LCD 设备） =====

/** 深色 Primary */
val DarkPrimary = Color(0xFFA0CAFD)
val DarkOnPrimary = Color(0xFF003258)
val DarkPrimaryContainer = Color(0xFF00497D)
val DarkOnPrimaryContainer = Color(0xFFD1E4FF)

/** 深色 Secondary */
val DarkSecondary = Color(0xFFBBC7DB)
val DarkOnSecondary = Color(0xFF253140)
val DarkSecondaryContainer = Color(0xFF3B4858)
val DarkOnSecondaryContainer = Color(0xFFD7E3F7)

/** 深色 Tertiary */
val DarkTertiary = Color(0xFFD6BEE4)
val DarkOnTertiary = Color(0xFF3B2948)
val DarkTertiaryContainer = Color(0xFF523F5F)
val DarkOnTertiaryContainer = Color(0xFFF2DAFF)

/** 深色 Error */
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

/** 深色 Background / Surface（LCD） */
val DarkBackgroundLcd = Color(0xFF121212)
val DarkOnBackground = Color(0xFFE2E2E6)
val DarkSurface = Color(0xFF1E1E1E)
val DarkOnSurface = Color(0xFFE2E2E6)
val DarkSurfaceVariant = Color(0xFF44474E)
val DarkOnSurfaceVariant = Color(0xFFC4C6D0)
val DarkOutline = Color(0xFF8E9099)
val DarkOutlineVariant = Color(0xFF44474E)

/** 深色 Inverse */
val DarkInverseSurface = Color(0xFFE2E2E6)
val DarkInverseOnSurface = Color(0xFF2F3033)
val DarkInversePrimary = Color(0xFF1565C0)

// ===== OLED 专用背景色 =====

/** 深色 Background — OLED 纯黑 */
val DarkBackgroundOled = Color(0xFF000000)

// ===== 全屏浏览专用 =====

/** 全屏浏览背景 — 纯黑（减少 OLED 功耗） */
val ViewerBackground = Color(0xFF000000)

/** 全屏 UI 覆盖层半透明 */
val ViewerOverlay = Color(0x66000000)

// ===== 高对比度色彩覆盖 =====

/** 高对比度浅色 — 提高文字-背景对比度 */
val HighContrastLightOnBackground = Color(0xFF000000)
val HighContrastLightOnSurface = Color(0xFF000000)
val HighContrastLightOnSurfaceVariant = Color(0xFF1A1A1A)

/** 高对比度深色 — 提高文字-背景对比度 */
val HighContrastDarkOnBackground = Color(0xFFFFFFFF)
val HighContrastDarkOnSurface = Color(0xFFFFFFFF)
val HighContrastDarkOnSurfaceVariant = Color(0xFFE0E0E0)
