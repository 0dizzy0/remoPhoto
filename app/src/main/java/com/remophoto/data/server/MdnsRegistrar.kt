package com.remophoto.data.server

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import com.remophoto.util.AppLogger
import com.remophoto.util.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * mDNS 服务注册器
 *
 * 在 HTTP Server 启动时将本设备注册为 `_remophoto._tcp` 服务，
 * 使局域网内其他 remoPhoto 客户端可自动发现本设备。
 *
 * 用法：
 * ```
 * val registrar = MdnsRegistrar(context)
 * registrar.register(port = 8080, deviceName = "我的手机")
 * // ... HTTP Server 运行中 ...
 * registrar.unregister()
 * ```
 */
class MdnsRegistrar(private val context: Context) {

    companion object {
        private const val TAG = "MdnsRegistrar"
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null

    private val _registered = MutableStateFlow(false)
    val registered: StateFlow<Boolean> = _registered.asStateFlow()

    /**
     * 注册 mDNS 服务
     *
     * @param port HTTP Server 监听端口
     * @param deviceName 设备显示名称
     * @return 是否注册成功
     */
    fun register(port: Int, deviceName: String): Boolean {
        return try {
            AppLogger.i(TAG, "正在注册 mDNS 服务: port=$port, name=$deviceName")

            val lanIp = getLanIp() ?: run {
                AppLogger.w(TAG, "无法获取局域网 IP，mDNS 注册跳过")
                return false
            }

            val inetAddr: InetAddress = InetAddress.getByName(lanIp)
            jmdns = JmDNS.create(inetAddr)

            // 构建服务信息
            val props: MutableMap<String, Any?> = mutableMapOf(
                "deviceName" to deviceName,
                "version" to "1.0.0",
                // 用稳定实例 ID 过滤自身；仅比较 IP 在多网卡/IPv6 场景下不可靠。
                "instanceId" to Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
            )

            serviceInfo = ServiceInfo.create(
                Constants.MDNS_SERVICE_TYPE,
                "remoPhoto-${deviceName}",
                port,
                0,  // weight
                0,  // priority
                props as Map<String, *>
            )

            jmdns?.registerService(serviceInfo)
            _registered.value = true

            AppLogger.i(TAG, "mDNS 服务已注册: ${Constants.MDNS_SERVICE_TYPE} @ $lanIp:$port")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "mDNS 注册失败", e)
            _registered.value = false
            false
        }
    }

    /**
     * 注销 mDNS 服务
     */
    fun unregister() {
        try {
            jmdns?.unregisterService(serviceInfo)
            jmdns?.close()
            jmdns = null
            serviceInfo = null
            _registered.value = false
            AppLogger.i(TAG, "mDNS 服务已注销")
        } catch (e: Exception) {
            AppLogger.e(TAG, "mDNS 注销失败", e)
        }
    }

    /**
     * 更新 TXT 记录（设备名称变更时使用）
     */
    fun updateDeviceName(newName: String) {
        try {
            serviceInfo?.setText(
                mapOf<String, Any?>(
                    "deviceName" to newName,
                    "version" to "1.0.0",
                    "instanceId" to Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.ANDROID_ID
                    )
                )
            )
            AppLogger.d(TAG, "mDNS TXT 记录已更新: $newName")
        } catch (e: Exception) {
            AppLogger.e(TAG, "更新 mDNS TXT 失败", e)
        }
    }

    private fun getLanIp(): String? {
        // 方式 1: 通过 WiFiManager 获取（可能因隐私限制返回 0）
        try {
            val ipInt = wifiManager.connectionInfo.ipAddress
            AppLogger.d(TAG, "WiFiManager.ipAddress 原始值: $ipInt (0x${ipInt.toString(16)})")
            if (ipInt != 0) {
                val ip = InetAddress.getByAddress(
                    byteArrayOf(
                        (ipInt and 0xff).toByte(),
                        (ipInt shr 8 and 0xff).toByte(),
                        (ipInt shr 16 and 0xff).toByte(),
                        (ipInt shr 24 and 0xff).toByte()
                    )
                ).hostAddress
                AppLogger.d(TAG, "WiFiManager 解析 IP: $ip, isLanIp=${ip != null && isLanIp(ip!!)}")
                if (ip != null && isLanIp(ip)) {
                    AppLogger.i(TAG, "LAN IP (WiFiManager): $ip")
                    return ip
                }
            } else {
                AppLogger.w(TAG, "WiFiManager.ipAddress 返回 0，回退到 NetworkInterface 枚举")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "WiFiManager 获取 IP 异常: ${e.message}")
        }
        // 方式 2: 枚举网络接口回退（Android 10+ WiFiManager 可能返回 0.0.0.0）
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val allIps = mutableListOf<String>()
            for (iface in interfaces) {
                val ifName = iface.displayName ?: "unknown"
                for (addr in iface.inetAddresses) {
                    val ip = addr.hostAddress ?: continue
                    allIps.add("$ifName=$ip")
                    if (!addr.isLoopbackAddress && addr.address.size == 4) {
                        AppLogger.d(TAG, "NetworkInterface 候选 IP: $ifName → $ip, isLanIp=${isLanIp(ip)}")
                        if (isLanIp(ip)) {
                            AppLogger.i(TAG, "LAN IP (NetworkInterface): $ip @ $ifName")
                            return ip
                        }
                    }
                }
            }
            AppLogger.w(TAG, "NetworkInterface 枚举完成，共 ${allIps.size} 个地址，无 LAN IP。全部地址: $allIps")
        } catch (e: Exception) {
            AppLogger.e(TAG, "NetworkInterface 枚举异常", e)
        }
        AppLogger.e(TAG, "无法获取任何 LAN IP！请检查 WiFi 连接")
        return null
    }

    private fun isLanIp(ip: String) =
        ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172.")
}
