package com.remophoto.data.server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.remophoto.util.AppLogger
import java.net.InetAddress

/**
 * WiFi 锁管理器
 *
 * 管理 Android WiFi 相关锁，确保 mDNS 和 HTTP Server 在后台运行时
 * WiFi 保持连接状态、多播数据包可正常收发。
 *
 * 三个锁：
 * - MulticastLock：允许接收 mDNS 多播数据包（mDNS 必需）
 * - WifiLock（高功耗模式）：保持 WiFi 在屏幕关闭时不休眠
 * - PARTIAL_WAKE_LOCK：允许 HTTP 接收线程在息屏后继续运行
 */
class WifiLockManager(context: Context) {

    companion object {
        private const val TAG = "WifiLockManager"
        private const val LOCK_TAG = "remoPhoto:mDNS"
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    private val powerManager: PowerManager by lazy {
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 获取 MulticastLock（mDNS 多播必需）
     */
    fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock(LOCK_TAG).apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld != true) multicastLock?.acquire()
            AppLogger.d(TAG, "MulticastLock 已获取")
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取 MulticastLock 失败", e)
        }
    }

    /**
     * 释放 MulticastLock
     */
    fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
            AppLogger.d(TAG, "MulticastLock 已释放")
        } catch (e: Exception) {
            AppLogger.e(TAG, "释放 MulticastLock 失败", e)
        }
    }

    /**
     * 获取 WifiLock（高功耗模式，保持 WiFi 连接）
     */
    fun acquireWifiLock() {
        try {
            if (wifiLock == null) {
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    LOCK_TAG
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
            AppLogger.d(TAG, "WifiLock 已获取")
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取 WifiLock 失败", e)
        }
    }

    /**
     * 释放 WifiLock
     */
    fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
            AppLogger.d(TAG, "WifiLock 已释放")
        } catch (e: Exception) {
            AppLogger.e(TAG, "释放 WifiLock 失败", e)
        }
    }

    fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$LOCK_TAG:http").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
            AppLogger.i(TAG, "PARTIAL_WAKE_LOCK 已获取")
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取 PARTIAL_WAKE_LOCK 失败", e)
        }
    }

    fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            AppLogger.i(TAG, "PARTIAL_WAKE_LOCK 已释放")
        } catch (e: Exception) {
            AppLogger.e(TAG, "释放 PARTIAL_WAKE_LOCK 失败", e)
        }
    }

    /**
     * 释放所有锁
     */
    fun releaseAll() {
        releaseMulticastLock()
        releaseWifiLock()
        releaseWakeLock()
    }

    /**
     * 获取当前局域网 IP 地址
     */
    fun getLanIp(): String? {
        // 方式 1: 通过 WiFiManager 获取（可能因隐私限制返回 0）
        try {
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt != 0) {
                val ip = InetAddress.getByAddress(
                    byteArrayOf(
                        (ipInt and 0xff).toByte(),
                        (ipInt shr 8 and 0xff).toByte(),
                        (ipInt shr 16 and 0xff).toByte(),
                        (ipInt shr 24 and 0xff).toByte()
                    )
                ).hostAddress
                if (ip != null && (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                    return ip
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "WiFiManager 获取 LAN IP 失败: ${e.message}")
        }
        // 方式 2: 枚举网络接口回退（Android 10+ WiFiManager 可能返回 0.0.0.0）
        try {
            for (iface in java.net.NetworkInterface.getNetworkInterfaces()) {
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr.address.size == 4) {
                        val ip = addr.hostAddress
                        if (ip != null && (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "NetworkInterface 枚举 LAN IP 失败: ${e.message}")
        }
        return null
    }
}
