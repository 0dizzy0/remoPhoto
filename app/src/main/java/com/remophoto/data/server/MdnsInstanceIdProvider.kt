package com.remophoto.data.server

import android.content.Context
import com.remophoto.util.AppLogger
import java.util.UUID

/**
 * 提供应用作用域的随机实例标识，用于 mDNS 发现时过滤本机服务。
 *
 * 该标识保存在应用私有 SharedPreferences 中，不读取硬件或系统设备标识，
 * 也不会写入日志或数据库导出文件。
 */
internal object MdnsInstanceIdProvider {
    private const val TAG = "MdnsInstanceId"
    private const val PREFERENCES_NAME = "mdns_identity"
    private const val KEY_INSTANCE_ID = "instance_id"

    @Volatile
    private var cachedInstanceId: String? = null

    fun get(context: Context): String {
        cachedInstanceId?.let { return it }

        return synchronized(this) {
            cachedInstanceId ?: loadOrCreate(context.applicationContext).also {
                cachedInstanceId = it
            }
        }
    }

    private fun loadOrCreate(context: Context): String {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.getString(KEY_INSTANCE_ID, null)
            ?.takeIf(String::isNotBlank)
            ?.let {
                AppLogger.d(TAG, "复用应用私有 mDNS 实例标识")
                return it
            }

        val instanceId = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_INSTANCE_ID, instanceId).apply()
        AppLogger.i(TAG, "已生成应用私有 mDNS 实例标识")
        return instanceId
    }
}
