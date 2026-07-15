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

    /**
     * SMB 媒体引用只保存连接 ID、不可逆媒体键和版本，不携带端点、账号或远端路径。
     */
    data class Smb(
        val connectionId: Long,
        val opaqueMediaKey: String,
        val variant: RemoteMediaVariant,
        val versionToken: String,
    ) : RemoteMediaRef {
        init {
            require(connectionId > 0L) { "SMB connectionId 必须大于 0" }
            require(OPAQUE_KEY.matches(opaqueMediaKey)) { "SMB 媒体键格式无效" }
            require(VERSION_TOKEN.matches(versionToken)) { "SMB 版本标识格式无效" }
        }

        override val storageValue: String = buildString {
            append(SCHEME)
            append(connectionId)
            append('/')
            append(opaqueMediaKey)
            append('/')
            append(variant.name.lowercase())
            append('/')
            append(versionToken)
        }

        /** Coil memory/disk cache 共用的确定性键。 */
        val cacheKey: String = "smb:$connectionId:$opaqueMediaKey:${variant.name}:$versionToken"

        fun original(): Smb = if (variant == RemoteMediaVariant.ORIGINAL) this else copy(
            variant = RemoteMediaVariant.ORIGINAL,
        )

        companion object {
            const val SCHEME = "smb-media://"
            private val OPAQUE_KEY = Regex("[a-f0-9]{64}")
            private val VERSION_TOKEN = Regex("[a-z0-9-]{1,64}")
            private val STORAGE = Regex(
                "^smb-media://([1-9][0-9]*)/([a-f0-9]{64})/(original|thumbnail)/([a-z0-9-]{1,64})$"
            )

            fun parse(storageValue: String): Smb? {
                val match = STORAGE.matchEntire(storageValue) ?: return null
                val connectionId = match.groupValues[1].toLongOrNull() ?: return null
                val variant = when (match.groupValues[3]) {
                    "original" -> RemoteMediaVariant.ORIGINAL
                    "thumbnail" -> RemoteMediaVariant.THUMBNAIL
                    else -> return null
                }
                return Smb(
                    connectionId = connectionId,
                    opaqueMediaKey = match.groupValues[2],
                    variant = variant,
                    versionToken = match.groupValues[4],
                )
            }
        }
    }
}

fun String.isRemoteMediaAddress(): Boolean =
    startsWith("http://") || startsWith("https://") || startsWith(RemoteMediaRef.Smb.SCHEME)

fun String.remoteMediaCacheKey(suffix: String = ""): String =
    RemoteMediaRef.Smb.parse(this)?.let { it.cacheKey + suffix } ?: this + suffix

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
