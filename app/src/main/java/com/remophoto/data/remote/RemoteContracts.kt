package com.remophoto.data.remote

import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType

/** 协议无关的远程相册记录。remoteKey 只由对应协议适配器解释。 */
data class RemoteAlbumRecord(
    val remoteKey: String,
    val name: String,
    val imageCount: Int,
    val parentRemoteKey: String? = null,
    val coverMediaKey: String? = null,
    val lastModified: Long = 0L,
)

/** 协议无关的远程媒体记录。 */
data class RemoteMediaRecord(
    val remoteKey: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
)

data class RemoteMediaPage(
    val items: List<RemoteMediaRecord>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
)

enum class RemoteMediaVariant {
    ORIGINAL,
    THUMBNAIL,
}

/**
 * 可持久化的媒体引用。M1 仅适配现有 HTTP URL；M3 将新增不含端点和凭据的 SMB 引用。
 */
sealed interface RemoteMediaRef {
    val storageValue: String

    data class HttpUrl(override val storageValue: String) : RemoteMediaRef
}

enum class RemoteErrorCategory {
    AUTH_FAILED,
    HOST_UNREACHABLE,
    SHARE_NOT_FOUND,
    ACCESS_DENIED,
    TIMEOUT,
    UNSUPPORTED_DIALECT,
    PATH_INVALID,
    CANCELLED,
    RESOURCE_LIMIT,
    UNSUPPORTED_PROTOCOL,
    UNKNOWN,
}

class RemoteDataException(
    val category: RemoteErrorCategory,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 远程目录协议适配器。
 *
 * 分页只是 HTTP 适配器的读取能力，不代表 SMB 必须按服务端分页；M3 的 SMB 实现会从目录快照读取。
 */
interface RemoteCatalogSource {
    val type: RemoteType

    suspend fun testConnection(connection: RemoteConnectionEntity): Boolean

    suspend fun fetchAlbums(connection: RemoteConnectionEntity): List<RemoteAlbumRecord>

    suspend fun fetchMediaPage(
        connection: RemoteConnectionEntity,
        albumRemoteKey: String,
        page: Int,
        pageSize: Int,
    ): RemoteMediaPage

    fun mediaRef(
        connection: RemoteConnectionEntity,
        mediaRemoteKey: String,
        variant: RemoteMediaVariant,
    ): RemoteMediaRef
}

class RemoteSourceRouter(sources: List<RemoteCatalogSource>) {
    private val sourcesByType = sources.associateBy(RemoteCatalogSource::type)

    init {
        require(sourcesByType.size == sources.size) { "每种远程协议只能注册一个数据源" }
    }

    fun sourceFor(type: RemoteType): RemoteCatalogSource = sourcesByType[type]
        ?: throw RemoteDataException(
            category = RemoteErrorCategory.UNSUPPORTED_PROTOCOL,
            message = "远程协议尚未启用: $type",
        )
}
