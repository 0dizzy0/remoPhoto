package com.remophoto.domain.usecase

import android.content.Context
import androidx.room.withTransaction
import com.remophoto.data.local.AppDatabase
import com.remophoto.data.local.dao.AlbumDao
import com.remophoto.data.local.dao.ImageDao
import com.remophoto.data.local.dao.RemoteConnectionDao
import com.remophoto.data.local.dao.RepositoryDao
import com.remophoto.data.local.entity.AlbumEntity
import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.data.local.entity.ImageEntity
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.remote.RemoteMediaRef
import com.remophoto.data.remote.RemoteMediaVariant
import com.remophoto.data.remote.smb.SmbCatalogScanner
import com.remophoto.data.remote.smb.SmbCatalogSnapshot
import com.remophoto.data.remote.smb.SmbPathCodec
import com.remophoto.util.AppLogger
import com.remophoto.util.Constants
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncSmbRepositoryUseCase(
    private val context: Context,
    private val database: AppDatabase,
    private val scanner: SmbCatalogScanner,
    private val albumDao: AlbumDao,
    private val imageDao: ImageDao,
    private val repositoryDao: RepositoryDao,
    private val connectionDao: RemoteConnectionDao,
) {
    data class Status(
        val phase: String,
        val discoveredImages: Int,
        val indexedImages: Int,
        val totalImages: Int?,
        val checkedDirectories: Int,
    )

    data class Result(val albumCount: Int, val imageCount: Int)

    suspend fun execute(
        connection: RemoteConnectionEntity,
        repositoryId: Long,
        onStatus: (Status) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        require(connection.type == RemoteType.SMB) { "连接类型不是 SMB" }
        val repository = checkNotNull(repositoryDao.getRepositoryById(repositoryId)) { "仓库不存在" }
        require(repository.remoteConnectionId == connection.id) { "仓库与 SMB 连接不匹配" }
        val startedAt = System.currentTimeMillis()
        val spoolDir = File(context.cacheDir, "smb-scan-spool").apply { mkdirs() }
        cleanupStaleSpools(spoolDir, repositoryId)
        val spool = File(spoolDir, "repo-$repositoryId-${System.currentTimeMillis()}.bin")
        try {
            onStatus(Status("遍历目录", 0, 0, null, 0))
            val snapshot = scanner.scanToSpool(connection, spool) { directories, images ->
                onStatus(Status("遍历目录", images, 0, null, directories))
            }
            onStatus(
                Status(
                    "建立索引",
                    snapshot.imageCount,
                    0,
                    snapshot.imageCount,
                    snapshot.checkedDirectories,
                )
            )
            applySnapshot(connection, repositoryId, snapshot, onStatus)
            connectionDao.updateStatus(connection.id, ConnectionStatus.CONNECTED)
            connectionDao.updateLastConnectedTime(connection.id, System.currentTimeMillis())
            val result = Result(snapshot.albums.size, snapshot.imageCount)
            onStatus(
                Status(
                    "完成",
                    result.imageCount,
                    result.imageCount,
                    result.imageCount,
                    snapshot.checkedDirectories,
                )
            )
            AppLogger.i(
                TAG,
                "SMB 索引刷新完成: connectionId=${connection.id}, repositoryId=$repositoryId, " +
                    "albums=${result.albumCount}, images=${result.imageCount}, " +
                    "elapsedMs=${System.currentTimeMillis() - startedAt}",
            )
            result
        } catch (cancelled: CancellationException) {
            AppLogger.i(TAG, "SMB 索引刷新取消: connectionId=${connection.id}, repositoryId=$repositoryId")
            throw cancelled
        } catch (error: Exception) {
            runCatching { connectionDao.updateStatus(connection.id, ConnectionStatus.ERROR) }
            AppLogger.e(
                TAG,
                "SMB 索引刷新失败: connectionId=${connection.id}, repositoryId=$repositoryId, " +
                    "category=${error.javaClass.simpleName}",
            )
            throw error
        } finally {
            if (spool.exists() && !spool.delete()) {
                AppLogger.w(TAG, "SMB 快照临时文件清理失败: repositoryId=$repositoryId")
            }
        }
    }

    private suspend fun applySnapshot(
        connection: RemoteConnectionEntity,
        repositoryId: Long,
        snapshot: SmbCatalogSnapshot,
        onStatus: (Status) -> Unit,
    ) {
        database.withTransaction {
            val currentRepository = repositoryDao.getRepositoryById(repositoryId)
            check(currentRepository?.remoteConnectionId == connection.id) {
                "SMB 仓库已被删除或连接已变更"
            }
            val existingAlbums = albumDao.getAlbumsByRepositoryList(repositoryId)
            val existingByPath = existingAlbums.associateBy(AlbumEntity::directoryPath)
            val albumIdByRelativePath = mutableMapOf<String, Long>()
            val entityByRelativePath = mutableMapOf<String, AlbumEntity>()

            // 父目录先插入，第二遍再建立父子 ID，重复刷新复用原 ID 和排序配置。
            snapshot.albums.sortedBy { album -> album.relativePath.count { it == '/' } }.forEach { album ->
                val storagePath = SmbPathCodec.albumStorageValue(connection.id, album.relativePath)
                val existing = existingByPath[storagePath]
                val cover = album.coverOpaqueKey?.let { opaque ->
                    RemoteMediaRef.Smb(
                        connectionId = connection.id,
                        opaqueMediaKey = opaque,
                        variant = RemoteMediaVariant.THUMBNAIL,
                        versionToken = checkNotNull(album.coverVersionToken),
                    ).storageValue
                }
                val entity = AlbumEntity(
                    id = existing?.id ?: 0L,
                    name = album.name,
                    directoryPath = storagePath,
                    repositoryId = repositoryId,
                    parentAlbumId = null,
                    coverImagePath = cover,
                    sortOrder = existing?.sortOrder,
                    imageCount = album.imageCount,
                    lastModified = album.lastModified,
                )
                val id = if (existing == null) albumDao.insert(entity) else {
                    albumDao.update(entity)
                    existing.id
                }
                albumIdByRelativePath[album.relativePath] = id
                entityByRelativePath[album.relativePath] = entity.copy(id = id)
            }

            val parentUpdates = snapshot.albums.mapNotNull { album ->
                val parentPath = album.parentRelativePath ?: return@mapNotNull null
                val parentId = albumIdByRelativePath[parentPath] ?: return@mapNotNull null
                entityByRelativePath[album.relativePath]?.copy(parentAlbumId = parentId)
            }
            if (parentUpdates.isNotEmpty()) albumDao.updateAll(parentUpdates)

            imageDao.deleteByRepository(repositoryId)
            var indexed = 0
            SmbCatalogScanner.readSpoolBatches(snapshot.spoolFile, Constants.DB_BATCH_SIZE) { records ->
                val entities = records.mapNotNull { record ->
                    val albumId = albumIdByRelativePath[record.parentRelativePath]
                        ?: return@mapNotNull null
                    ImageEntity(
                        filePath = RemoteMediaRef.Smb(
                            connectionId = connection.id,
                            opaqueMediaKey = record.opaqueMediaKey,
                            variant = RemoteMediaVariant.ORIGINAL,
                            versionToken = record.versionToken,
                        ).storageValue,
                        fileName = record.fileName,
                        fileSize = record.fileSize,
                        lastModified = record.lastModified,
                        mimeType = record.mimeType,
                        width = 0,
                        height = 0,
                        albumId = albumId,
                        repositoryId = repositoryId,
                    )
                }
                if (entities.isNotEmpty()) imageDao.upsertAll(entities)
                indexed += entities.size
                onStatus(
                    Status(
                        "建立索引",
                        snapshot.imageCount,
                        indexed,
                        snapshot.imageCount,
                        snapshot.checkedDirectories,
                    )
                )
                AppLogger.i(
                    TAG,
                    "SMB 索引批次写入: connectionId=${connection.id}, repositoryId=$repositoryId, " +
                        "batch=${entities.size}, indexed=$indexed/${snapshot.imageCount}",
                )
            }

            val retainedIds = albumIdByRelativePath.values.toSet()
            val staleAlbums = existingAlbums.filter { it.id !in retainedIds }
            if (staleAlbums.isNotEmpty()) albumDao.deleteAll(staleAlbums)
            repositoryDao.updateScanInfo(repositoryId, System.currentTimeMillis(), indexed)
        }
    }

    private fun cleanupStaleSpools(spoolDir: File, repositoryId: Long) {
        spoolDir.listFiles { file -> file.name.startsWith("repo-$repositoryId-") }
            ?.forEach { stale ->
                if (stale.delete()) {
                    AppLogger.i(TAG, "已清理 SMB 异常退出临时文件: repositoryId=$repositoryId")
                }
            }
    }

    private companion object {
        const val TAG = "SyncSmbRepository"
    }
}
