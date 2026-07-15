package com.remophoto.data.remote

import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType

/** 把现有 remoPhoto HTTP DTO/API 收敛在协议适配器内部。 */
class HttpRemoteCatalogSource(
    private val api: RemoteHttpApi,
) : RemoteCatalogSource {
    override val type: RemoteType = RemoteType.HTTP_MDNS

    override suspend fun testConnection(connection: RemoteConnectionEntity): Boolean =
        api.ping(connection.host, connection.port)

    override suspend fun fetchAlbums(
        connection: RemoteConnectionEntity,
    ): List<RemoteAlbumRecord> = api.getAlbums(connection.host, connection.port).map { dto ->
        RemoteAlbumRecord(
            remoteKey = dto.id.toString(),
            name = dto.name,
            imageCount = dto.imageCount,
            parentRemoteKey = dto.parentAlbumId?.toString(),
            coverMediaKey = dto.coverImageId?.toString(),
            lastModified = dto.lastModified,
        )
    }

    override suspend fun fetchMediaPage(
        connection: RemoteConnectionEntity,
        albumRemoteKey: String,
        page: Int,
        pageSize: Int,
    ): RemoteMediaPage {
        val albumId = albumRemoteKey.toLongOrNull()
            ?: throw RemoteDataException(
                category = RemoteErrorCategory.PATH_INVALID,
                message = "HTTP 相册标识无效",
            )
        val response = api.getImages(connection.host, connection.port, albumId, page, pageSize)
        return RemoteMediaPage(
            items = response.images.map { dto ->
                RemoteMediaRecord(
                    remoteKey = dto.id.toString(),
                    fileName = dto.fileName,
                    fileSize = dto.fileSize,
                    lastModified = dto.lastModified,
                    width = dto.width,
                    height = dto.height,
                    mimeType = dto.mimeType,
                )
            },
            totalCount = response.totalCount,
            page = response.page,
            pageSize = response.pageSize,
        )
    }

    override fun mediaRef(
        connection: RemoteConnectionEntity,
        mediaRemoteKey: String,
        variant: RemoteMediaVariant,
    ): RemoteMediaRef {
        val imageId = mediaRemoteKey.toLongOrNull()
            ?: throw RemoteDataException(
                category = RemoteErrorCategory.PATH_INVALID,
                message = "HTTP 图片标识无效",
            )
        val suffix = if (variant == RemoteMediaVariant.THUMBNAIL) "/thumb" else ""
        return RemoteMediaRef.HttpUrl(
            "http://${connection.host}:${connection.port}/api/image/$imageId$suffix",
        )
    }
}
