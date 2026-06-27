package com.remophoto.data.repository

import android.content.Context
import com.remophoto.util.AppLogger
import java.util.UUID

/**
 * Generates a stable, non-identifying name for LAN discovery.
 *
 * The alias replaces Build.MODEL as the default mDNS name so the app does not
 * broadcast the device model unless the user explicitly chooses such a name.
 */
internal object DeviceAliasProvider {

    private const val TAG = "DeviceAliasProvider"
    private const val PREFERENCES_NAME = "privacy_defaults"
    private const val KEY_DEVICE_ALIAS = "device_alias"

    @Synchronized
    fun get(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        preferences.getString(KEY_DEVICE_ALIAS, null)?.let { existing ->
            if (existing.isNotBlank()) return existing
        }

        val suffix = UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(6)
            .uppercase()
        val alias = "remoPhoto-$suffix"
        preferences.edit().putString(KEY_DEVICE_ALIAS, alias).apply()
        AppLogger.i(TAG, "已生成随机局域网设备别名")
        return alias
    }
}
