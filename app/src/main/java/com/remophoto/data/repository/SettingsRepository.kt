package com.remophoto.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.remophoto.domain.model.SortOrder
import com.remophoto.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Context 扩展：DataStore 单例 */
private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 全局设置数据仓库
 *
 * 使用 DataStore 持久化用户偏好设置。
 */
class SettingsRepository(private val context: Context) {

    companion object {
        private val KEY_DEFAULT_SORT_ORDER = stringPreferencesKey("default_sort_order")
        private val KEY_ALBUMS_PER_PAGE = intPreferencesKey("albums_per_page")
        private val KEY_SLIDESHOW_INTERVAL = intPreferencesKey("slideshow_interval")
        private val KEY_USE_VOLUME_KEYS = booleanPreferencesKey("use_volume_keys")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DARK_MODE_TYPE = stringPreferencesKey("dark_mode_type")
        private val KEY_HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
    }

    /** 默认排序方式 */
    val defaultSortOrder: Flow<SortOrder> = context.dataStore.data.map { prefs ->
        SortOrder.fromName(prefs[KEY_DEFAULT_SORT_ORDER])
    }

    /** 每页相册数量 */
    val albumsPerPage: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_ALBUMS_PER_PAGE] ?: Constants.DEFAULT_ALBUMS_PER_PAGE
    }

    /** 自动播放间隔（秒） */
    val slideshowInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_SLIDESHOW_INTERVAL] ?: 3
    }

    /** 是否使用音量键翻页 */
    val useVolumeKeys: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_USE_VOLUME_KEYS] ?: true
    }

    /** 主题模式：light / dark / system */
    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: "system"
    }

    /** 深色背景类型：auto / oled / lcd（仅深色模式时生效） */
    val darkModeType: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DARK_MODE_TYPE] ?: "auto"
    }

    /** 高对比度模式 */
    val highContrast: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_HIGH_CONTRAST] ?: false
    }

    // ===== 写操作 =====

    suspend fun setDefaultSortOrder(order: SortOrder) {
        context.dataStore.edit { it[KEY_DEFAULT_SORT_ORDER] = order.name }
    }

    suspend fun setAlbumsPerPage(count: Int) {
        context.dataStore.edit { it[KEY_ALBUMS_PER_PAGE] = count }
    }

    suspend fun setSlideshowInterval(seconds: Int) {
        context.dataStore.edit { it[KEY_SLIDESHOW_INTERVAL] = seconds }
    }

    suspend fun setUseVolumeKeys(enabled: Boolean) {
        context.dataStore.edit { it[KEY_USE_VOLUME_KEYS] = enabled }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    suspend fun setDarkModeType(type: String) {
        context.dataStore.edit { it[KEY_DARK_MODE_TYPE] = type }
    }

    suspend fun setHighContrast(enabled: Boolean) {
        context.dataStore.edit { it[KEY_HIGH_CONTRAST] = enabled }
    }
}
