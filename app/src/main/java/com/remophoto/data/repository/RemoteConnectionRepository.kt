package com.remophoto.data.repository

import com.remophoto.data.local.dao.RemoteConnectionDao
import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.remote.RemoteAlbumRecord
import com.remophoto.data.remote.RemoteMediaPage
import com.remophoto.data.remote.RemoteMediaRef
import com.remophoto.data.remote.RemoteMediaVariant
import com.remophoto.data.remote.RemoteSourceRouter
import com.remophoto.util.AppLogger
import kotlinx.coroutines.CancellationException

/**
 * 协议无关的远程仓库入口。
 *
 * 连接状态、重试与观测日志在此统一处理；具体 HTTP/SMB 类型只存在于 source adapter 内。
 */
class RemoteConnectionRepository(
    private val sourceRouter: RemoteSourceRouter,
    private val connectionDao: RemoteConnectionDao,
) {
    companion object {
        private const val TAG = "RemoteConnectionRepository"
        private const val MAX_ATTEMPTS = 2
    }

    /** 保留当前手动添加 HTTP 设备的 API；M2 会改为完整 RemoteConfig。 */
    suspend fun testConnection(host: String, port: Int): Boolean {
        val transient = RemoteConnectionEntity(
            type = RemoteType.HTTP_MDNS,
            host = host,
            port = port,
            displayName = "",
            addedTime = 0L,
        )
        return try {
            sourceRouter.sourceFor(transient.type).testConnection(transient)
        } catch (error: Exception) {
            AppLogger.w(TAG, "连接测试失败: category=${error.javaClass.simpleName}")
            false
        }
    }

    suspend fun fetchAlbums(connection: RemoteConnectionEntity): List<RemoteAlbumRecord> =
        runObserved(connection, "fetch-albums") {
            sourceRouter.sourceFor(connection.type).fetchAlbums(connection)
        }.also { albums ->
            AppLogger.i(TAG, "远程相册读取成功: connectionId=${connection.id}, count=${albums.size}")
        }

    suspend fun fetchMediaPage(
        connection: RemoteConnectionEntity,
        albumRemoteKey: String,
        page: Int = 1,
        pageSize: Int = 50,
    ): RemoteMediaPage = retry(connection.id, "fetch-media-page") {
        sourceRouter.sourceFor(connection.type).fetchMediaPage(
            connection = connection,
            albumRemoteKey = albumRemoteKey,
            page = page,
            pageSize = pageSize,
        )
    }.also { response ->
        AppLogger.i(
            TAG,
            "远程图片页读取成功: connectionId=${connection.id}, " +
                "page=${response.page}, count=${response.items.size}/${response.totalCount}",
        )
    }

    fun mediaRef(
        connection: RemoteConnectionEntity,
        mediaRemoteKey: String,
        variant: RemoteMediaVariant,
    ): RemoteMediaRef = sourceRouter.sourceFor(connection.type)
        .mediaRef(connection, mediaRemoteKey, variant)

    suspend fun checkConnection(connection: RemoteConnectionEntity): ConnectionStatus = try {
        val ok = sourceRouter.sourceFor(connection.type).testConnection(connection)
        val status = if (ok) ConnectionStatus.CONNECTED else ConnectionStatus.ERROR
        updateConnectionStatus(connection.id, status)
        status
    } catch (_: Exception) {
        updateConnectionStatus(connection.id, ConnectionStatus.ERROR)
        ConnectionStatus.ERROR
    }

    private suspend fun <T> runObserved(
        connection: RemoteConnectionEntity,
        stage: String,
        block: suspend () -> T,
    ): T = try {
        retry(connection.id, stage, block).also {
            updateConnectionStatus(connection.id, ConnectionStatus.CONNECTED)
        }
    } catch (error: Exception) {
        updateConnectionStatus(connection.id, ConnectionStatus.ERROR)
        AppLogger.e(
            TAG,
            "远程操作失败: connectionId=${connection.id}, stage=$stage, " +
                "category=${error.javaClass.simpleName}",
        )
        throw error
    }

    private suspend fun updateConnectionStatus(connectionId: Long, status: ConnectionStatus) {
        try {
            connectionDao.updateStatus(connectionId, status)
            if (status == ConnectionStatus.CONNECTED) {
                connectionDao.updateLastConnectedTime(connectionId, System.currentTimeMillis())
            }
        } catch (error: Exception) {
            AppLogger.e(
                TAG,
                "连接状态更新失败: connectionId=$connectionId, category=${error.javaClass.simpleName}",
            )
        }
    }

    private suspend fun <T> retry(
        connectionId: Long,
        stage: String,
        block: suspend () -> T,
    ): T {
        var lastException: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (cancelled: CancellationException) {
                AppLogger.i(
                    TAG,
                    "远程操作取消: connectionId=$connectionId, stage=$stage, attempt=${attempt + 1}",
                )
                throw cancelled
            } catch (error: Exception) {
                lastException = error
                AppLogger.w(
                    TAG,
                    "远程操作重试: connectionId=$connectionId, stage=$stage, " +
                        "attempt=${attempt + 1}/$MAX_ATTEMPTS, category=${error.javaClass.simpleName}",
                )
                if (attempt < MAX_ATTEMPTS - 1) {
                    kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
            }
        }
        throw lastException ?: IllegalStateException("远程操作失败: stage=$stage")
    }
}
