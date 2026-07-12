package com.remophoto.data.remote.smb

import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.remote.RemoteAlbumRecord
import com.remophoto.data.remote.RemoteCatalogSource
import com.remophoto.data.remote.RemoteDataException
import com.remophoto.data.remote.RemoteErrorCategory
import com.remophoto.data.remote.RemoteMediaPage
import com.remophoto.data.remote.RemoteMediaRef
import com.remophoto.data.remote.RemoteMediaVariant

/**
 * Router 中的 SMB 连接检查适配器。目录读取由快照扫描器完成，不模拟 HTTP 分页。
 */
class SmbRemoteCatalogSource(
    private val sessionManager: SmbSessionManager,
) : RemoteCatalogSource {
    override val type: RemoteType = RemoteType.SMB

    override suspend fun testConnection(connection: RemoteConnectionEntity): Boolean {
        sessionManager.testConnection(connection)
        return true
    }

    override suspend fun fetchAlbums(connection: RemoteConnectionEntity): List<RemoteAlbumRecord> =
        unsupportedSnapshotOperation()

    override suspend fun fetchMediaPage(
        connection: RemoteConnectionEntity,
        albumRemoteKey: String,
        page: Int,
        pageSize: Int,
    ): RemoteMediaPage = unsupportedSnapshotOperation()

    override fun mediaRef(
        connection: RemoteConnectionEntity,
        mediaRemoteKey: String,
        variant: RemoteMediaVariant,
    ): RemoteMediaRef = unsupportedSnapshotOperation()

    private fun unsupportedSnapshotOperation(): Nothing = throw RemoteDataException(
        RemoteErrorCategory.UNSUPPORTED_PROTOCOL,
        "SMB 目录必须通过快照扫描读取",
    )
}
