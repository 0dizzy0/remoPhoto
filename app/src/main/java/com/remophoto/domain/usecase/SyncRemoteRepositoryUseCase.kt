package com.remophoto.domain.usecase

import com.remophoto.data.local.dao.AlbumDao
import com.remophoto.data.local.dao.ImageDao
import com.remophoto.data.local.dao.RepositoryDao
import com.remophoto.data.local.entity.AlbumEntity
import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.data.local.entity.ImageEntity
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.remote.RemoteAlbumDto
import com.remophoto.data.repository.RemoteConnectionRepository
import com.remophoto.data.remote.RemoteImageDto
import com.remophoto.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 同步远程仓库数据到本地 Room DB
 *
 * 将远程 HTTP API 返回的 DTO 映射为 Room Entity 并 upsert 到本地数据库。
 * 支持幂等操作——重复同步不会产生重复数据。
 */
class SyncRemoteRepositoryUseCase(
    private val albumDao: AlbumDao,
    private val imageDao: ImageDao,
    private val repositoryDao: RepositoryDao,
    private val remoteRepository: RemoteConnectionRepository
) {

    companion object {
        private const val TAG = "SyncRemoteRepo"
    }

    /**
     * 同步远程仓库的全部相册元数据
     *
     * @param connection 远程连接实体
     * @param localRepoId 本地 RepositoryEntity.id
     * @return 同步的相册数量
     */
    suspend fun syncAlbums(
        connection: RemoteConnectionEntity,
        localRepoId: Long
    ): Int = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "开始同步远程相册: ${connection.host}:${connection.port}")

        val dtos: List<RemoteAlbumDto> = remoteRepository.fetchAlbums(connection)

        // 远程相册不能再次作为本机数据源对外共享。同步时完整替换该远程仓库
        // 的索引，避免旧 albumId 对应的图片残留并在反复浏览后持续重复。
        imageDao.deleteByRepository(localRepoId)
        albumDao.deleteByRepository(localRepoId)

        // 第一遍插入并建立“服务端相册 ID -> 本地相册 ID”映射。
        val localIdByRemoteId = mutableMapOf<Long, Long>()
        dtos.forEach { dto ->
            val localId = albumDao.insert(AlbumEntity(
                id = 0,
                name = dto.name,
                directoryPath = "remote://${connection.host}:${connection.port}/album/${dto.id}",
                repositoryId = localRepoId,
                parentAlbumId = null,
                coverImagePath = dto.coverImageId?.let {
                    "http://${connection.host}:${connection.port}/api/image/$it/thumb"
                },
                imageCount = dto.imageCount
            ))
            localIdByRemoteId[dto.id] = localId
        }

        // 第二遍修复父子关系。直接写服务端 parentAlbumId 会与本地自增 ID 混用。
        dtos.forEach { dto ->
            val localId = localIdByRemoteId[dto.id] ?: return@forEach
            val localParentId = dto.parentAlbumId?.let(localIdByRemoteId::get)
            albumDao.updateParentAlbum(localId, localParentId)
        }

        val totalImages = dtos.sumOf { it.imageCount }
        repositoryDao.updateImageCount(localRepoId, totalImages)

        AppLogger.i(
            TAG,
            "远程相册同步完成: ${dtos.size} 个相册, $totalImages 张图片, " +
                "父子映射=${dtos.count { it.parentAlbumId != null }}, " +
                "封面=${dtos.count { it.coverImageId != null }}"
        )
        dtos.size
    }

    /**
     * 同步远程相册内的图片元数据
     *
     * @param connection 远程连接实体
     * @param localAlbumId 本地 AlbumEntity.id
     * @param remoteAlbumId 远程相册 ID
     * @param localRepoId 本地 RepositoryEntity.id
     * @param host 远程主机
     * @param port 远程端口
     * @param pageSize 每页数量
     * @return 同步的图片数量
     */
    suspend fun syncImages(
        connection: RemoteConnectionEntity,
        localAlbumId: Long,
        remoteAlbumId: Long,
        localRepoId: Long,
        host: String,
        port: Int,
        pageSize: Int = 50
    ): Int = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "开始同步远程图片: album=$remoteAlbumId")

        var page = 1
        val remoteImages = mutableListOf<RemoteImageDto>()

        while (true) {
            val response = remoteRepository.fetchImages(connection, remoteAlbumId, page, pageSize)
            remoteImages += response.images

            if (response.images.size < pageSize || remoteImages.size >= response.totalCount) {
                break
            }
            page++
        }

        // 全部分页成功后再替换，网络中断时保留现有离线索引。
        val entities = remoteImages.distinctBy { it.id }.map { dto ->
            ImageEntity(
                id = 0,
                filePath = "http://$host:$port/api/image/${dto.id}",
                fileName = dto.fileName,
                fileSize = dto.fileSize,
                lastModified = dto.lastModified,
                mimeType = dto.mimeType,
                width = dto.width,
                height = dto.height,
                albumId = localAlbumId,
                repositoryId = localRepoId
            )
        }
        imageDao.deleteByAlbum(localAlbumId)
        imageDao.upsertAll(entities)
        val totalSynced = entities.size
        albumDao.updateImageCount(localAlbumId, totalSynced)
        AppLogger.i(TAG, "远程图片同步完成: album=$remoteAlbumId, $totalSynced 张")
        totalSynced
    }

    /**
     * 获取远程图片 URL
     */
    fun getRemoteImageUrl(host: String, port: Int, remoteImageId: Long): String {
        return remoteRepository.getImageUrl(host, port, remoteImageId)
    }

    /**
     * 检查远程连接状态
     */
    suspend fun checkConnection(connection: RemoteConnectionEntity): ConnectionStatus {
        return remoteRepository.checkConnection(connection)
    }
}
