package com.remophoto.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.remophoto.domain.model.SortOrder
import com.remophoto.domain.model.AlbumSortOrder
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
        private val KEY_ALBUM_SORT_ORDER = stringPreferencesKey("album_sort_order")
        private val KEY_ALBUMS_PER_PAGE = intPreferencesKey("albums_per_page")
        private val KEY_SLIDESHOW_INTERVAL = intPreferencesKey("slideshow_interval")
        private val KEY_USE_VOLUME_KEYS = booleanPreferencesKey("use_volume_keys")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DARK_MODE_TYPE = stringPreferencesKey("dark_mode_type")
        private val KEY_HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        // Phase 4: 远程服务设置
        private val KEY_HTTP_SERVER_ENABLED = booleanPreferencesKey("http_server_enabled")
        private val KEY_HTTP_SERVER_PORT = intPreferencesKey("http_server_port")
        private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
    }

    /** 默认排序方式 */
    val defaultSortOrder: Flow<SortOrder> = context.dataStore.data.map { prefs ->
        SortOrder.fromName(prefs[KEY_DEFAULT_SORT_ORDER])
    }

    /** 相册列表排序（不影响相册内图片排序） */
    val albumSortOrder: Flow<AlbumSortOrder> = context.dataStore.data.map { prefs ->
        AlbumSortOrder.fromName(prefs[KEY_ALBUM_SORT_ORDER])
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

    // ===== Phase 4: 远程服务设置 =====

    /** HTTP 服务是否开启 */
    val httpServerEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_HTTP_SERVER_ENABLED] ?: false
    }

    /** HTTP 服务端口 */
    val httpServerPort: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_HTTP_SERVER_PORT] ?: Constants.REMOTE_HTTP_PORT
    }

    /** 设备名称（mDNS 广播用） */
    val deviceName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_NAME] ?: android.os.Build.MODEL ?: "Android 设备"
    }

    // ===== 写操作 =====

    suspend fun setDefaultSortOrder(order: SortOrder) {
        context.dataStore.edit { it[KEY_DEFAULT_SORT_ORDER] = order.name }
    }

    suspend fun setAlbumSortOrder(order: AlbumSortOrder) {
        context.dataStore.edit { it[KEY_ALBUM_SORT_ORDER] = order.name }
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

    // ===== Phase 4: 远程服务设置写操作 =====

    suspend fun setHttpServerEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_HTTP_SERVER_ENABLED] = enabled }
    }

    suspend fun setHttpServerPort(port: Int) {
        context.dataStore.edit { it[KEY_HTTP_SERVER_PORT] = port }
    }

    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { it[KEY_DEVICE_NAME] = name }
    }
}
