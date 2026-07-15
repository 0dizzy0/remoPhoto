package com.remophoto.data.remote.smb

import android.content.Context
import com.remophoto.data.server.WifiLockManager
import com.remophoto.util.AppLogger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.jmdns.JmDNS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class DiscoveredSmbServer(
    val displayName: String,
    val host: String,
    val port: Int = SmbLanDiscovery.DEFAULT_PORT,
    val source: Source,
) {
    enum class Source { MDNS, SUBNET_PROBE }
}

/** 一次性发现局域网 SMB2/3 服务；发现结果只用于预填，始终保留手动输入。 */
class SmbLanDiscovery(context: Context) {
    private val lockManager = WifiLockManager(context.applicationContext)

    suspend fun discover(): List<DiscoveredSmbServer> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        lockManager.acquireMulticastLock()
        try {
            val localIp = lockManager.getLanIp()
            val mdns = discoverMdns(localIp)
            val subnet = probeSubnet(localIp)
            val merged = mergeSmbDiscoveryResults(mdns + subnet)
            AppLogger.i(
                TAG,
                "SMB 局域网发现完成: mdns=${mdns.size}, probes=${subnet.size}, " +
                    "unique=${merged.size}, elapsedMs=${System.currentTimeMillis() - startedAt}",
            )
            merged
        } finally {
            lockManager.releaseMulticastLock()
        }
    }

    private fun discoverMdns(localIp: String?): List<DiscoveredSmbServer> {
        var jmdns: JmDNS? = null
        return try {
            jmdns = localIp?.let { JmDNS.create(InetAddress.getByName(it)) } ?: JmDNS.create()
            jmdns.list(SERVICE_TYPE, MDNS_TIMEOUT_MS).flatMap { info ->
                info.inet4Addresses.mapNotNull { address ->
                    val host = address.hostAddress ?: return@mapNotNull null
                    if (host == localIp) return@mapNotNull null
                    DiscoveredSmbServer(
                        displayName = info.name.ifBlank { "SMB 设备" },
                        host = host,
                        port = info.port.takeIf { it in 1..65535 } ?: DEFAULT_PORT,
                        source = DiscoveredSmbServer.Source.MDNS,
                    )
                }
            }
        } catch (error: Exception) {
            AppLogger.w(TAG, "SMB mDNS 发现失败: cause=${error.javaClass.simpleName}")
            emptyList()
        } finally {
            runCatching { jmdns?.close() }
        }
    }

    private suspend fun probeSubnet(localIp: String?): List<DiscoveredSmbServer> = coroutineScope {
        val ownIp = localIp ?: return@coroutineScope emptyList()
        val prefix = ownIp.substringBeforeLast('.', missingDelimiterValue = "")
        if (prefix.isBlank()) return@coroutineScope emptyList()
        val semaphore = Semaphore(PROBE_CONCURRENCY)
        (1..254).map { suffix ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val host = "$prefix.$suffix"
                    if (host == ownIp || !isPortOpen(host, DEFAULT_PORT)) null
                    else DiscoveredSmbServer(
                        displayName = "SMB 设备 $suffix",
                        host = host,
                        source = DiscoveredSmbServer.Source.SUBNET_PROBE,
                    )
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun isPortOpen(host: String, port: Int): Boolean = try {
        Socket().use { socket -> socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        const val DEFAULT_PORT = 445
        private const val TAG = "SmbLanDiscovery"
        private const val SERVICE_TYPE = "_smb._tcp.local."
        private const val MDNS_TIMEOUT_MS = 2_500L
        private const val PROBE_TIMEOUT_MS = 280
        private const val PROBE_CONCURRENCY = 32
    }
}

internal fun mergeSmbDiscoveryResults(results: List<DiscoveredSmbServer>): List<DiscoveredSmbServer> = results
    .groupBy { "${it.host}:${it.port}" }
    .values
    .map { matches -> matches.minBy { it.source.ordinal } }
    .sortedWith(compareBy<DiscoveredSmbServer>({ ipv4SortKey(it.host) }, { it.port }))

private fun ipv4SortKey(host: String): Long = host.split('.')
    .takeIf { it.size == 4 }
    ?.mapNotNull(String::toIntOrNull)
    ?.takeIf { it.size == 4 && it.all { value -> value in 0..255 } }
    ?.fold(0L) { value, part -> (value shl 8) + part }
    ?: Long.MAX_VALUE
