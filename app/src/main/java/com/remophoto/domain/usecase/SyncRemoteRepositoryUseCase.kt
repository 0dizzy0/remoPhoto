package com.remophoto.domain.usecase

import com.remophoto.data.local.dao.AlbumDao
import com.remophoto.data.local.dao.ImageDao
import com.remophoto.data.local.dao.RepositoryDao
import com.remophoto.data.local.AppDatabase
import androidx.room.withTransaction
import com.remophoto.data.local.entity.AlbumEntity
import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.data.local.entity.ImageEntity
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.remote.RemoteAlbumRecord
import com.remophoto.data.remote.RemoteMediaRecord
import com.remophoto.data.remote.RemoteMediaVariant
import com.remophoto.data.repository.RemoteConnectionRepository
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
    private val database: AppDatabase,
    private val albumDao: AlbumDao,
    private val imageDao: ImageDao,
    private val repositoryDao: RepositoryDao,
    private val remoteRepository: RemoteConnectionRepository,
    private val syncSmbRepository: SyncSmbRepositoryUseCase? = null,
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
        if (connection.type == RemoteType.SMB) {
            return@withContext checkNotNull(syncSmbRepository) { "SMB 快照同步尚未配置" }
                .execute(connection, localRepoId)
                .albumCount
        }
        val startedAt = System.currentTimeMillis()
        AppLogger.i(TAG, "开始同步远程相册: connectionId=${connection.id}, repoId=$localRepoId")

        val records: List<RemoteAlbumRecord> = remoteRepository.fetchAlbums(connection)
        val existingAlbums = albumDao.getAlbumsByRepositoryList(localRepoId)
        val existingByPath = existingAlbums.associateBy { it.directoryPath }
        val localIdByRemoteKey = mutableMapOf<String, Long>()
        val baseEntityByRemoteKey = mutableMapOf<String, AlbumEntity>()
        val existingUpdates = mutableListOf<AlbumEntity>()
        val newRecords = mutableListOf<RemoteAlbumRecord>()
        val newEntities = mutableListOf<AlbumEntity>()

        for (record in records) {
            // HTTP adapter 的 remoteKey 保持原数字 ID，因此 M1 不改变已有 Room 标识。
            val path = "remote://${connection.host}:${connection.port}/album/${record.remoteKey}"
            val existing = existingByPath[path]
            val entity = AlbumEntity(
                id = existing?.id ?: 0,
                name = record.name,
                directoryPath = path,
                repositoryId = localRepoId,
                parentAlbumId = null,
                coverImagePath = record.coverMediaKey?.let { mediaKey ->
                    remoteRepository.mediaRef(
                        connection,
                        mediaKey,
                        RemoteMediaVariant.THUMBNAIL,
                    ).storageValue
                } ?: existing?.coverImagePath,
                sortOrder = existing?.sortOrder,
                imageCount = record.imageCount,
                lastModified = record.lastModified.takeIf { it > 0L }
                    ?: existing?.lastModified
                    ?: 0L,
            )
            baseEntityByRemoteKey[record.remoteKey] = entity
            if (existing == null) {
                newRecords += record
                newEntities += entity
            } else {
                localIdByRemoteKey[record.remoteKey] = existing.id
                existingUpdates += entity
            }
        }

        var staleAlbums: List<AlbumEntity> = emptyList()
        database.withTransaction {
            if (existingUpdates.isNotEmpty()) albumDao.updateAll(existingUpdates)
            if (newEntities.isNotEmpty()) {
                val insertedIds = albumDao.insertAll(newEntities)
                newRecords.zip(insertedIds).forEach { (record, localId) ->
                    localIdByRemoteKey[record.remoteKey] = localId
                }
            }

            // 仅有父相册的记录需要第二次批量更新。
            val parentUpdates = records.mapNotNull { record ->
                val parentId = record.parentRemoteKey?.let(localIdByRemoteKey::get)
                    ?: return@mapNotNull null
                val localId = localIdByRemoteKey[record.remoteKey] ?: return@mapNotNull null
                baseEntityByRemoteKey[record.remoteKey]?.copy(id = localId, parentAlbumId = parentId)
            }
            if (parentUpdates.isNotEmpty()) albumDao.updateAll(parentUpdates)

            val retainedIds = localIdByRemoteKey.values.toSet()
            staleAlbums = existingAlbums.filter { it.id !in retainedIds }
            if (staleAlbums.isNotEmpty()) {
                staleAlbums.forEach { imageDao.deleteByAlbum(it.id) }
                albumDao.deleteAll(staleAlbums)
            }

            val totalImages = records.sumOf { it.imageCount }
            repositoryDao.updateScanInfo(localRepoId, System.currentTimeMillis(), totalImages)
        }

        val totalImages = records.sumOf { it.imageCount }
        AppLogger.i(
            TAG,
            "远程相册同步完成: connectionId=${connection.id}, ${records.size} 个相册, " +
                "$totalImages 张图片, 父子映射=${records.count { it.parentRemoteKey != null }}, " +
                "封面=${records.count { it.coverMediaKey != null }}, " +
                "复用=${existingUpdates.size}, 新增=${newEntities.size}, 删除=${staleAlbums.size}, " +
                "elapsedMs=${System.currentTimeMillis() - startedAt}"
        )
        records.size
    }

    /**
     * 同步远程相册内的图片元数据
     *
     * @param connection 远程连接实体
     * @param localAlbumId 本地 AlbumEntity.id
     * @param remoteAlbumKey 远程相册 opaque key
     * @param localRepoId 本地 RepositoryEntity.id
     * @param pageSize 每页数量
     * @return 同步的图片数量
     */
    suspend fun syncImages(
        connection: RemoteConnectionEntity,
        localAlbumId: Long,
        remoteAlbumKey: String,
        localRepoId: Long,
        pageSize: Int = 50
    ): Int = withContext(Dispatchers.IO) {
        if (connection.type == RemoteType.SMB) {
            // SMB 在仓库级快照中已一次性建立全部图片索引，进入相册时只读 Room。
            return@withContext imageDao.getImageCountByAlbum(localAlbumId)
        }
        AppLogger.i(TAG, "开始同步远程图片: connectionId=${connection.id}, albumId=$localAlbumId")

        var page = 1
        val remoteImages = mutableListOf<RemoteMediaRecord>()

        while (true) {
            val response = remoteRepository.fetchMediaPage(
                connection,
                remoteAlbumKey,
                page,
                pageSize,
            )
            remoteImages += response.items

            if (response.items.size < pageSize || remoteImages.size >= response.totalCount) {
                break
            }
            page++
        }

        // 全部分页成功后再替换，网络中断时保留现有离线索引。
        val entities = remoteImages.distinctBy { it.remoteKey }.map { record ->
            ImageEntity(
                id = 0,
                filePath = remoteRepository.mediaRef(
                    connection,
                    record.remoteKey,
                    RemoteMediaVariant.ORIGINAL,
                ).storageValue,
                fileName = record.fileName,
                fileSize = record.fileSize,
                lastModified = record.lastModified,
                mimeType = record.mimeType,
                width = record.width,
                height = record.height,
                albumId = localAlbumId,
                repositoryId = localRepoId
            )
        }
        imageDao.deleteByAlbum(localAlbumId)
        imageDao.upsertAll(entities)
        val totalSynced = entities.size
        albumDao.updateImageCount(localAlbumId, totalSynced)
        AppLogger.i(
            TAG,
            "远程图片同步完成: connectionId=${connection.id}, albumId=$localAlbumId, " +
                "count=$totalSynced",
        )
        totalSynced
    }

    /**
     * 检查远程连接状态
     */
    suspend fun checkConnection(connection: RemoteConnectionEntity): ConnectionStatus {
        return remoteRepository.checkConnection(connection)
    }
}
