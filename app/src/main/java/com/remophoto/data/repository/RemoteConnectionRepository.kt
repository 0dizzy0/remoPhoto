package com.remophoto.data.repository

import com.remophoto.data.local.dao.RemoteConnectionDao
import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.remote.RemoteAlbumDto
import com.remophoto.data.remote.RemoteHttpClient
import com.remophoto.data.remote.RemoteImageListResponse
import com.remophoto.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 远程连接数据仓库
 *
 * 组合 RemoteHttpClient 和 RemoteConnectionDao，提供远程仓库的高级操作：
 * - 连接测试（ping）
 * - 相册列表同步（fetch → upsert local DB）
 * - 图片列表同步
 * - 连接状态更新
 *
 * 内置重试逻辑：最多 2 次重试，指数退避（1s, 2s）。
 */
class RemoteConnectionRepository(
    private val httpClient: RemoteHttpClient,
    private val connectionDao: RemoteConnectionDao
) {

    companion object {
        private const val TAG = "RemoteConnectionRepository"
        private const val MAX_RETRIES = 2
    }

    /**
     * 测试远程连接
     *
     * @return true 表示连接可达
     */
    suspend fun testConnection(host: String, port: Int): Boolean {
        return try {
            httpClient.ping(host, port)
        } catch (e: Exception) {
            AppLogger.w(TAG, "连接测试失败: $host:$port — ${e.message}")
            false
        }
    }

    /**
     * 从远程设备获取相册列表
     *
     * @param connection 远程连接实体（含连接元信息）
     * @return 远程相册 DTO 列表
     */
    suspend fun fetchAlbums(connection: RemoteConnectionEntity): List<RemoteAlbumDto> {
        return try {
            retry("fetchAlbums ${connection.host}:${connection.port}") {
                val albums = httpClient.getAlbums(connection.host, connection.port)
                updateConnectionStatus(connection.id, ConnectionStatus.CONNECTED)
                AppLogger.i(TAG, "获取相册列表成功: ${albums.size} 个相册")
                albums
            }
        } catch (e: Exception) {
            updateConnectionStatus(connection.id, ConnectionStatus.ERROR)
            AppLogger.e(TAG, "获取相册列表最终失败，连接已标记为 ERROR", e)
            throw e
        }
    }

    /**
     * 从远程设备获取相册内图片列表
     */
    suspend fun fetchImages(
        connection: RemoteConnectionEntity,
        albumId: Long,
        page: Int = 1,
        pageSize: Int = 50
    ): RemoteImageListResponse {
        return retry("fetchImages ${connection.host}:${connection.port} album=$albumId") {
            val response = httpClient.getImages(connection.host, connection.port, albumId, page, pageSize)
            AppLogger.i(TAG, "获取图片列表成功: album=$albumId, ${response.images.size}/${response.totalCount}")
            response
        }
    }

    /**
     * 获取远程图片流 URL 字符串
     */
    fun getImageUrl(host: String, port: Int, imageId: Long): String {
        return "http://$host:$port/api/image/$imageId"
    }

    /**
     * 获取远程缩略图 URL 字符串
     */
    fun getThumbnailUrl(host: String, port: Int, imageId: Long): String {
        return "http://$host:$port/api/image/$imageId/thumb"
    }

    /**
     * Ping 并更新连接状态
     */
    suspend fun checkConnection(connection: RemoteConnectionEntity): ConnectionStatus {
        return try {
            val ok = httpClient.ping(connection.host, connection.port)
            val status = if (ok) ConnectionStatus.CONNECTED else ConnectionStatus.ERROR
            updateConnectionStatus(connection.id, status)
            status
        } catch (e: Exception) {
            updateConnectionStatus(connection.id, ConnectionStatus.ERROR)
            ConnectionStatus.ERROR
        }
    }

    // ===== Private =====

    private suspend fun updateConnectionStatus(connectionId: Long, status: ConnectionStatus) {
        try {
            connectionDao.updateStatus(connectionId, status)
            if (status == ConnectionStatus.CONNECTED) {
                connectionDao.updateLastConnectedTime(connectionId, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "更新连接状态失败", e)
        }
    }

    private suspend fun <T> retry(tag: String, block: suspend () -> T): T {
        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                AppLogger.w(TAG, "$tag 第 ${attempt + 1} 次尝试失败: ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(1000L * (attempt + 1)) // 1s, 2s
                }
            }
        }
        throw lastException ?: RuntimeException("$tag 失败")
    }
}
