package com.remophoto.data.remote.smb

import com.remophoto.data.local.dao.AlbumDao
import com.remophoto.data.local.dao.ImageDao
import com.remophoto.data.local.dao.RemoteConnectionDao
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.remote.RemoteDataException
import com.remophoto.data.remote.RemoteErrorCategory
import com.remophoto.data.remote.RemoteMediaRef

data class ResolvedSmbMedia(
    val connection: RemoteConnectionEntity,
    val serverPath: String,
    val mimeType: String,
)

class SmbMediaResolver(
    private val imageDao: ImageDao,
    private val albumDao: AlbumDao,
    private val connectionDao: RemoteConnectionDao,
) {
    suspend fun resolve(ref: RemoteMediaRef.Smb): ResolvedSmbMedia {
        val image = imageDao.getImageByPath(ref.original().storageValue) ?: invalidReference()
        val album = albumDao.getAlbumById(image.albumId) ?: invalidReference()
        val albumLocator = SmbPathCodec.parseAlbumStorageValue(album.directoryPath) ?: invalidReference()
        if (albumLocator.connectionId != ref.connectionId) invalidReference()
        val connection = connectionDao.getConnectionById(ref.connectionId) ?: invalidReference()
        if (connection.type != RemoteType.SMB) invalidReference()

        val relativeFile = SmbPathCodec.joinRelative(albumLocator.relativeDirectory, image.fileName)
        if (SmbPathCodec.opaqueKey(relativeFile) != ref.opaqueMediaKey) invalidReference()
        if (SmbPathCodec.versionToken(image.fileSize, image.lastModified) != ref.versionToken) invalidReference()
        return ResolvedSmbMedia(
            connection = connection,
            serverPath = SmbPathCodec.serverPath(connection.rootPath, relativeFile),
            mimeType = image.mimeType,
        )
    }

    private fun invalidReference(): Nothing = throw RemoteDataException(
        RemoteErrorCategory.PATH_INVALID,
        "SMB 媒体引用无效或已过期",
    )
}
