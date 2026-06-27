package com.remophoto.data.server

import android.content.Context
import com.remophoto.util.AppLogger
import com.remophoto.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
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
    context: Context,
    private val wifiLockManager: WifiLockManager
) {

    companion object {
        private const val TAG = "MdnsDiscoveryService"
        private const val EXPIRY_MS = 120_000L // 120s 过期
        private const val ACTIVE_SCAN_INTERVAL_MS = 5_000L
        private const val ACTIVE_SCAN_TIMEOUT_MS = 2_000L
        private const val HTTP_FALLBACK_TIMEOUT_MS = 350
        private const val HTTP_FALLBACK_CONCURRENCY = 48
    }

    private var jmdns: JmDNS? = null
    private val discovered = ConcurrentHashMap<String, DiscoveredDevice>() // key = "host:port"
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private var localIp: String? = null  // 用于过滤自身
    private val localInstanceId = MdnsInstanceIdProvider.get(context)
    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeScanJob: Job? = null
    private var subnetFallbackCompleted = false

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /**
     * 开始扫描
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (_isScanning.value) {
                AppLogger.i(TAG, "mDNS 已在扫描，跳过重复启动")
                return@withContext true
            }
            AppLogger.i(TAG, "═════ 开始 mDNS 设备发现 ═════")
            subnetFallbackCompleted = false
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
                    // 主动等待解析，弥补 Android 上 serviceResolved 偶发丢失。
                    jmdns?.requestServiceInfo(event.type, event.name, true, 3_000)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    AppLogger.d(TAG, "🗑 服务移除: ${event.name}")
                    val key = makeKey(event)
                    discovered.remove(key)
                    emitDevices()
                }

                override fun serviceResolved(event: ServiceEvent) {
                    AppLogger.d(TAG, "✅ serviceResolved: name=${event.name}, info=${event.info}")
                    handleResolved(event.info, event.name, "listener")
                }
            })
            AppLogger.i(TAG, "[步骤4] ServiceListener 已注册")

            _isScanning.value = true
            startActiveScan()
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
            activeScanJob?.cancelAndJoin()
            activeScanJob = null
            jmdns?.close()
            jmdns = null
            discovered.clear()
            lastSeen.clear()
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
        AppLogger.d(TAG, "手动刷新 mDNS 设备列表")
        scanScope.launch { performActiveScan() }
        emitDevices()
    }

    private fun startActiveScan() {
        activeScanJob?.cancel()
        activeScanJob = scanScope.launch {
            AppLogger.i(TAG, "主动 mDNS 扫描已启动: interval=${ACTIVE_SCAN_INTERVAL_MS}ms")
            while (isActive) {
                performActiveScan()
                delay(ACTIVE_SCAN_INTERVAL_MS)
            }
        }
    }

    private suspend fun performActiveScan() {
        try {
            val infos = jmdns?.list(Constants.MDNS_SERVICE_TYPE, ACTIVE_SCAN_TIMEOUT_MS) ?: emptyArray()
            AppLogger.d(TAG, "主动扫描返回 ${infos.size} 个服务")
            infos.forEach { handleResolved(it, it.name, "active-list") }
            expireStaleDevices()

            // 某些路由器/旧版服务会过滤或丢失 mDNS，但同网段 HTTP 仍可直连。
            // 首轮无外部设备时，仅扫描一次默认 8080 端口作为自动发现兜底。
            val hasExternalDevice = discovered.values.any {
                it.host.substringBefore('%') != localIp
            }
            if (!subnetFallbackCompleted && !hasExternalDevice) {
                subnetFallbackCompleted = true
                scanLocalSubnetFallback()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "主动 mDNS 扫描失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun scanLocalSubnetFallback() = coroutineScope {
        val ownIp = localIp ?: return@coroutineScope
        val prefix = ownIp.substringBeforeLast('.', missingDelimiterValue = "")
        if (prefix.isBlank()) return@coroutineScope

        AppLogger.i(TAG, "mDNS 无外部结果，启动 HTTP 网段兜底扫描: $prefix.0/24:8080")
        val semaphore = Semaphore(HTTP_FALLBACK_CONCURRENCY)
        (1..254).map { suffix ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val host = "$prefix.$suffix"
                    if (host != ownIp) probeRemoPhoto(host, Constants.REMOTE_HTTP_PORT)
                }
            }
        }.awaitAll()
        emitDevices()
        AppLogger.i(TAG, "HTTP 网段兜底扫描完成: 发现=${discovered.size}")
    }

    private fun probeRemoPhoto(host: String, port: Int) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("http://$host:$port/api").openConnection() as HttpURLConnection
            connection.connectTimeout = HTTP_FALLBACK_TIMEOUT_MS
            connection.readTimeout = HTTP_FALLBACK_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.useCaches = false
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optString("app") != "remoPhoto") return
            val key = makeKey(host, port)
            val displayName = json.optString("deviceName").ifBlank { host }
            discovered[key] = DiscoveredDevice(displayName, host, port, DiscoveryStatus.ACTIVE)
            lastSeen[key] = System.currentTimeMillis()
            AppLogger.i(TAG, "📡 HTTP 兜底发现: $displayName @ $host:$port")
        } catch (_: Exception) {
            // 大多数地址不可达是正常扫描结果，不逐个刷日志。
        } finally {
            connection?.disconnect()
        }
    }

    private fun handleResolved(info: ServiceInfo?, fallbackName: String, source: String) {
        if (info == null || info.inet4Addresses.isEmpty()) {
            AppLogger.w(TAG, "解析结果无 IPv4 地址: name=$fallbackName, source=$source")
            return
        }
        val host = info.inet4Addresses.firstNotNullOfOrNull { it.hostAddress } ?: return
        val instanceId = info.getPropertyString("instanceId")
        val isSelf = host.substringBefore('%') == localIp ||
            (!instanceId.isNullOrBlank() && instanceId == localInstanceId)
        if (isSelf) {
            AppLogger.d(TAG, "过滤自身服务: $fallbackName @ $host")
            return
        }
        val port = info.port
        val key = makeKey(host, port)
        val deviceName = info.getPropertyString("deviceName") ?: fallbackName
        discovered[key] = DiscoveredDevice(deviceName, host, port, DiscoveryStatus.ACTIVE)
        lastSeen[key] = System.currentTimeMillis()
        AppLogger.i(TAG, "📡 设备已解析[$source]: $deviceName @ $host:$port")
        emitDevices()
    }

    private fun expireStaleDevices() {
        val cutoff = System.currentTimeMillis() - EXPIRY_MS
        val expiredKeys = lastSeen.filterValues { it < cutoff }.keys
        expiredKeys.forEach {
            discovered.remove(it)
            lastSeen.remove(it)
        }
        if (expiredKeys.isNotEmpty()) {
            AppLogger.i(TAG, "已移除 ${expiredKeys.size} 个过期 mDNS 设备")
            emitDevices()
        }
    }

    private fun emitDevices() {
        _devices.value = discovered.values
            .filter { it.status == DiscoveryStatus.ACTIVE }
            .filter { device ->
                // 过滤本机自身：防止设备发现自己
                val isSelf = localIp != null && device.host.substringBefore('%') == localIp
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
