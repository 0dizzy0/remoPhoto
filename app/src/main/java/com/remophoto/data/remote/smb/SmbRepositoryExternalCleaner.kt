package com.remophoto.data.remote.smb

import android.content.Context
import androidx.work.WorkManager
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.memory.MemoryCache
import com.remophoto.data.local.dao.AlbumDao
import com.remophoto.data.local.dao.ImageDao
import com.remophoto.data.remote.RemoteMediaRef
import com.remophoto.data.repository.RemoteRepositoryExternalCleaner
import com.remophoto.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoilApi::class)
class SmbRepositoryExternalCleaner(
    context: Context,
    private val imageDao: ImageDao,
    private val albumDao: AlbumDao,
    private val remoteThumbnailLoader: () -> ImageLoader,
    private val remoteImageLoader: () -> ImageLoader,
) : RemoteRepositoryExternalCleaner {
    private val appContext = context.applicationContext
    private val pendingKeys = ConcurrentHashMap<Long, Set<String>>()

    override suspend fun prepare(repositoryId: Long) {
        val cancel = WorkManager.getInstance(appContext)
            .cancelUniqueWork(SmbRepositorySyncWorker.uniqueName(repositoryId))
        withContext(Dispatchers.IO) {
            runInterruptible { cancel.result.get(10, TimeUnit.SECONDS) }
        }
        val keys = buildSet {
            imageDao.getImagesByRepository(repositoryId).forEach { image ->
                RemoteMediaRef.Smb.parse(image.filePath)?.let { ref ->
                    add(ref.cacheKey)
                    add(ref.cacheKey + ":thumb")
                    add(ref.cacheKey + ":original")
                }
            }
            albumDao.getAlbumsByRepositoryList(repositoryId).forEach { album ->
                album.coverImagePath?.let(RemoteMediaRef.Smb::parse)?.let { ref ->
                    add(ref.cacheKey)
                    add(ref.cacheKey + ":cover")
                }
            }
        }
        pendingKeys[repositoryId] = keys
        AppLogger.i(TAG, "SMB 删除清理已准备: repositoryId=$repositoryId, cacheKeys=${keys.size}")
    }

    override suspend fun complete(repositoryId: Long) {
        val keys = pendingKeys.remove(repositoryId).orEmpty()
        removeKeys(remoteThumbnailLoader(), keys)
        removeKeys(remoteImageLoader(), keys)
        AppLogger.i(TAG, "SMB 外部缓存清理完成: repositoryId=$repositoryId, cacheKeys=${keys.size}")
    }

    private fun removeKeys(loader: ImageLoader, keys: Set<String>) {
        keys.forEach { key ->
            loader.memoryCache?.remove(MemoryCache.Key(key))
            loader.diskCache?.remove(key)
        }
    }

    private companion object {
        const val TAG = "SmbRepositoryCleaner"
    }
}
