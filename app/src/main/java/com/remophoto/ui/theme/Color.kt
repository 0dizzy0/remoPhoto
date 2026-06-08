package com.remophoto.ui.theme

import androidx.compose.ui.graphics.Color

// ===== 浅色主题色板 =====

/** 浅色主题背景色 — 近白 */
val LightBackground = Color(0xFFFAFAFA)

/** 浅色主题卡片/表面色 */
val LightSurface = Color(0xFFFFFFFF)

/** 浅色主题文字色 */
val LightOnBackground = Color(0xFF1A1A1A)

/** 浅色主题强调色 */
val LightPrimary = Color(0xFF1976D2)

/** 浅色主题次要文字 */
val LightOnSurfaceVariant = Color(0xFF666666)

// ===== 深色主题色板（LCD 设备） =====

/** 深色主题背景色 — 深灰（LCD 设备） */
val DarkBackgroundLcd = Color(0xFF121212)

/** 深色主题背景色 — 纯黑（OLED 设备） */
val DarkBackgroundOled = Color(0xFF000000)

/** 深色主题卡片/表面色 */
val DarkSurface = Color(0xFF1E1E1E)

/** 深色主题文字色 */
val DarkOnBackground = Color(0xFFE0E0E0)

/** 深色主题强调色 */
val DarkPrimary = Color(0xFF64B5F6)

/** 深色主题次要文字 */
val DarkOnSurfaceVariant = Color(0xFFAAAAAA)

// ===== 全屏浏览专用 =====

/** 全屏浏览背景 — 纯黑（减少 OLED 功耗） */
val ViewerBackground = Color(0xFF000000)

/** 全屏 UI 覆盖层半透明 */
val ViewerOverlay = Color(0x66000000)
