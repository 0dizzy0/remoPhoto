package com.remophoto.data.server

import com.remophoto.util.AppLogger
import com.remophoto.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/**
 * 局域网发现的设备信息
 */
data class DiscoveredDevice(
    /** 设备显示名称 */
    val displayName: String,
    /** 主机 IP 地址 */
    val host: String,
    /** HTTP 服务端口 */
    val port: Int,
    /** mDNS 解析状态 */
    val status: DiscoveryStatus = DiscoveryStatus.ACTIVE
)

enum class DiscoveryStatus {
    /** 已发现，可连接 */
    ACTIVE,
    /** 已过期（超过 120s 未收到广播） */
    EXPIRED
}

/**
 * mDNS 设备发现服务
 *
 * 扫描局域网内的 `_remophoto._tcp` 服务，发现其他运行 remoPhoto 的设备。
 *
 * 用法：
 * ```
 * val discovery = MdnsDiscoveryService(wifiLockManager)
 * discovery.start()
 * discovery.devices.collect { devices -> ... }
 * discovery.stop()
 * ```
 */
class MdnsDiscoveryService(
    private val wifiLockManager: WifiLockManager
) {

    companion object {
        private const val TAG = "MdnsDiscoveryService"
        private const val EXPIRY_MS = 120_000L // 120s 过期
    }

    private var jmdns: JmDNS? = null
    private val discovered = mutableMapOf<String, DiscoveredDevice>() // key = "host:port"
    private var localIp: String? = null  // 用于过滤自身

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /**
     * 开始扫描
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        try {
            AppLogger.i(TAG, "═════ 开始 mDNS 设备发现 ═════")
            AppLogger.i(TAG, "服务类型: ${Constants.MDNS_SERVICE_TYPE}")

            // 步骤 1: 获取 MulticastLock
            AppLogger.d(TAG, "[步骤1] 获取 MulticastLock...")
            wifiLockManager.acquireMulticastLock()

            // 步骤 2: 探测 LAN IP（保存用于过滤自身）
            AppLogger.d(TAG, "[步骤2] 探测局域网 IP...")
            localIp = wifiLockManager.getLanIp()
            AppLogger.i(TAG, "[步骤2] LAN IP 探测结果: ${localIp ?: "NULL (将使用默认 JmDNS.create())"}")

            // 步骤 3: 创建 JmDNS 实例（必须在 IO 线程，JmDNS 内部调用 InetAddress.getLocalHost()）
            AppLogger.d(TAG, "[步骤3] 创建 JmDNS 实例 (Dispatchers.IO)...")
            jmdns = try {
                if (localIp != null) {
                    AppLogger.d(TAG, "JmDNS.create(InetAddress.getByName($localIp))")
                    JmDNS.create(InetAddress.getByName(localIp))
                } else {
                    AppLogger.w(TAG, "无 LAN IP，JmDNS.create() 无参（监听所有接口）")
                    JmDNS.create()
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "JmDNS.create(InetAddress) 失败: ${e.message}，尝试无参")
                JmDNS.create()
            }
            AppLogger.i(TAG, "[步骤3] JmDNS 实例创建: ${if (jmdns != null) "成功" else "失败(NULL)"}")

            // 步骤 4: 添加 ServiceListener
            AppLogger.d(TAG, "[步骤4] 添加 ServiceListener...")
            jmdns?.addServiceListener(Constants.MDNS_SERVICE_TYPE, object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    AppLogger.i(TAG, "🔍 发现服务: name=${event.name}, type=${event.type}")
                    // 请求解析详细信息
                    jmdns?.requestServiceInfo(event.type, event.name)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    AppLogger.d(TAG, "🗑 服务移除: ${event.name}")
                    val key = makeKey(event)
                    discovered.remove(key)
                    emitDevices()
                }

                override fun serviceResolved(event: ServiceEvent) {
                    AppLogger.d(TAG, "✅ serviceResolved: name=${event.name}, info=${event.info}")
                    val info = event.info ?: run {
                        AppLogger.w(TAG, "serviceResolved: event.info 为 null")
                        return
                    }
                    if (info.inetAddresses.isNullOrEmpty()) {
                        AppLogger.w(TAG, "serviceResolved: inetAddresses 为空")
                        return
                    }

                    val addr = info.inetAddresses[0]
                    val port = info.port
                    val deviceName = info.getPropertyString("deviceName") ?: event.name
                    val host = addr.hostAddress ?: run {
                        AppLogger.w(TAG, "serviceResolved: hostAddress 为 null")
                        return
                    }

                    val key = makeKey(host, port)
                    discovered[key] = DiscoveredDevice(
                        displayName = deviceName,
                        host = host,
                        port = port,
                        status = DiscoveryStatus.ACTIVE
                    )
                    AppLogger.i(TAG, "📡 设备已解析: $deviceName @ $host:$port (key=$key)")
                    emitDevices()
                }
            })
            AppLogger.i(TAG, "[步骤4] ServiceListener 已注册")

            _isScanning.value = true
            AppLogger.i(TAG, "═════ mDNS 设备发现已启动 (isScanning=true) ═════")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ mDNS 发现启动失败", e)
            _isScanning.value = false
            false
        }
    }

    /**
     * 停止扫描
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            AppLogger.i(TAG, "停止 mDNS 设备发现...")
            jmdns?.close()
            jmdns = null
            discovered.clear()
            _devices.value = emptyList()
            _isScanning.value = false
            wifiLockManager.releaseMulticastLock()
            AppLogger.i(TAG, "mDNS 设备发现已停止")
        } catch (e: Exception) {
            AppLogger.e(TAG, "mDNS 停止失败", e)
        }
    }

    /**
     * 刷新设备列表（移除过期设备）
     */
    fun refresh() {
        // JmDNS 3.5.9 不提供 TTL 信息，过期由外部定时器处理
        emitDevices()
    }

    private fun emitDevices() {
        _devices.value = discovered.values
            .filter { it.status == DiscoveryStatus.ACTIVE }
            .filter { device ->
                // 过滤本机自身：防止设备发现自己
                val isSelf = localIp != null && device.host == localIp
                if (isSelf) {
                    AppLogger.d(TAG, "过滤自身: ${device.displayName} @ ${device.host}:${device.port}")
                }
                !isSelf
            }
            .sortedBy { it.displayName }
    }

    private fun makeKey(event: ServiceEvent): String {
        val info = event.info
        return if (info != null && info.inetAddresses.isNotEmpty()) {
            makeKey(info.inetAddresses[0].hostAddress ?: "unknown", info.port)
        } else {
            event.name
        }
    }

    private fun makeKey(host: String, port: Int) = "$host:$port"
}
