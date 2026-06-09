package com.remophoto.domain.usecase

import android.net.Uri
import com.remophoto.data.local.dao.AlbumDao
import com.remophoto.data.local.dao.ImageDao
import com.remophoto.data.local.dao.RepositoryDao
import com.remophoto.data.local.entity.AlbumEntity
import com.remophoto.data.scanner.FileScanner
import com.remophoto.util.AppLogger
import com.remophoto.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 扫描图片用例
 *
 * 协调 FileScanner + ImageDao + AlbumDao + RepositoryDao，
 * 完成完整的扫描工作流：
 * 1. 递归扫描目录，提取图片元数据
 * 2. 自动创建相册（按目录结构）
 * 3. 写入图片索引到 Room
 * 4. 更新仓库扫描信息
 */
class ScanImagesUseCase(
    private val fileScanner: FileScanner,
    private val imageDao: ImageDao,
    private val albumDao: AlbumDao,
    private val repositoryDao: RepositoryDao,
    private val albumCoverManager: AlbumCoverManager? = null
) {

    /**
     * 执行全量扫描
     *
     * @param repoId 仓库 ID
     * @param rootUri 根目录 URI
     * @param onProgress 进度回调（0.0 ~ 1.0）
     * @return 扫描到的图片数量
     */
    suspend fun executeFullScan(
        repoId: Long,
        rootUri: Uri,
        onProgress: (Float) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        onProgress(0f)

        // ===== 阶段 1：收集（不写 DB，旧数据完整保留） =====

        // 1a. 遍历目录树
        onProgress(0.02f)
        val collected = fileScanner.collectDirectoriesAndImages(
            rootUri = rootUri,
            onProgress = { fileCount ->
                val fraction = 0.02f + 0.28f * (fileCount.toFloat() / (fileCount + 100).toFloat())
                onProgress(fraction.coerceIn(0.02f, 0.30f))
            }
        )
        if (collected == null) {
            onProgress(1f)
            return@withContext 0
        }
        val (imageFiles, directories) = collected

        onProgress(0.32f)

        // 1b. 构建相册实体（内存中）
        val albumEntities = buildAlbumEntities(
            directories = directories,
            repositoryId = repoId,
            rootUriString = rootUri.toString()
        )

        onProgress(0.38f)

        // ===== 阶段 2：原子替换（旧数据在此刻才删除） =====

        // 2a. 删除旧数据
        imageDao.deleteByRepository(repoId)
        albumDao.deleteByRepository(repoId)

        onProgress(0.42f)

        // 2b. 插入新相册 → 获得真实 albumId 映射
        val albumIdMap = mutableMapOf<String, Long>()
        for ((_, entity) in albumEntities.withIndex()) {
            val id = albumDao.insert(entity)
            albumIdMap[entity.directoryPath] = id
        }

        AppLogger.i(TAG, "相册已插入: ${albumIdMap.size} 个, 映射键示例=[${albumIdMap.keys.take(2).joinToString(" | ")}]")

        onProgress(0.48f)

        // 2c. 处理图片元数据并写入 DB（用真实 albumIdMap）
        val totalImages = fileScanner.processImages(
            imageFiles = imageFiles,
            repositoryId = repoId,
            albumIdMap = albumIdMap,
            imageDao = imageDao,
            onProgress = { progress ->
                val fraction = if (progress.total > 0) {
                    0.48f + progress.current.toFloat() / progress.total.toFloat() * 0.42f
                } else 0.48f
                onProgress(fraction.coerceAtMost(0.90f))
            }
        )

        onProgress(0.90f)

        // ===== 阶段 3：统计更新 =====

        updateAlbumImageCounts()
        AppLogger.i(TAG, "开始封面选取: 共 ${albumIdMap.size} 个相册")
        albumCoverManager?.autoSelectAllCovers()
        // 验证封面设置结果
        for ((dirPath, albumId) in albumIdMap) {
            val album = albumDao.getAlbumById(albumId)
            if (album != null) {
                AppLogger.d(TAG,
                    "扫描结果验证: albumId=$albumId, dir=\"$dirPath\", " +
                    "name=\"${album.name}\", imageCount=${album.imageCount}, " +
                    "coverPath=${album.coverImagePath ?: "null"}"
                )
            }
        }
        repositoryDao.updateScanInfo(repoId, System.currentTimeMillis(), totalImages)

        onProgress(1f)
        totalImages
    }

    /**
     * 构建相册实体列表（仅内存操作，不写 DB）
     */
    private fun buildAlbumEntities(
        directories: List<Uri>,
        repositoryId: Long,
        rootUriString: String
    ): List<AlbumEntity> {
        val albumPathMap = mutableMapOf<String, AlbumEntity>()

        for ((index, dirUri) in directories.withIndex()) {
            val dirPath = dirUri.toString()
            val rawName = dirPath.substringAfterLast('/')
            val dirName = try {
                java.net.URLDecoder.decode(rawName, "UTF-8")
            } catch (_: Exception) {
                rawName
            }.ifBlank { "相册 ${index + 1}" }

            val parentPath = dirPath.substringBeforeLast('/')
            val parentAlbumId: Long? = if (parentPath.isNotBlank() && parentPath != rootUriString) {
                null // 父相册 ID 在插入后关联
            } else {
                null
            }

            albumPathMap[dirPath] = AlbumEntity(
                name = dirName,
                directoryPath = dirPath,
                repositoryId = repositoryId,
                parentAlbumId = null,
                coverImagePath = null,
                sortOrder = null,
                imageCount = 0
            )
        }

        // 第二遍：关联 parentAlbumId（根据目录层级）
        // 注意：此时相册尚未插入，无法获取真实 ID，parentAlbumId 在插入后通过目录路径关联
        // 简化处理：所有相册均为根级（parentAlbumId=null）

        return albumPathMap.values.toList()
    }

    /**
     * 执行增量扫描
     *
     * 仅处理修改时间晚于 [lastScanTime] 的文件。
     *
     * @param repoId 仓库 ID
     * @param rootUri 根目录 URI
     * @param lastScanTime 上次扫描时间戳（毫秒）
     * @param onProgress 进度回调
     * @return 新增/更新的图片数量
     */
    suspend fun executeIncrementalScan(
        repoId: Long,
        rootUri: Uri,
        lastScanTime: Long,
        onProgress: (Float) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        onProgress(0f)

        // 获取已有的相册映射
        val existingAlbums = albumDao.getAllAlbumsList()
        val albumIdMap = existingAlbums.associate { it.directoryPath to it.id }

        onProgress(0.2f)

        // 增量扫描
        val updatedCount = fileScanner.incrementalScan(
            rootUri = rootUri,
            repositoryId = repoId,
            albumIdMap = albumIdMap,
            lastScanTime = lastScanTime,
            imageDao = imageDao,
            onProgress = { progress ->
                val fraction = 0.2f + 0.6f * (progress.current.toFloat() / (progress.total.coerceAtLeast(1)).toFloat())
                onProgress(fraction)
            }
        )

        onProgress(0.9f)

        // 更新相册数量
        updateAlbumImageCounts()

        // 更新仓库扫描信息
        val totalImages = imageDao.getImageCountByRepository(repoId)
        repositoryDao.updateScanInfo(repoId, System.currentTimeMillis(), totalImages)

        onProgress(1f)
        updatedCount
    }

    /**
     * 从扫描到的目录列表创建相册实体
     *
     * @return 目录路径 → 相册 ID 的映射
     */
    private suspend fun createAlbumsFromDirectories(
        directories: List<Uri>,
        repositoryId: Long,
        rootUriString: String
    ): Map<String, Long> {
        val albumMap = mutableMapOf<String, Long>()

        for ((index, dirUri) in directories.withIndex()) {
            val dirPath = dirUri.toString()
            // URL 解码目录名（SAF URI 路径可能含 % 编码）
            val rawName = dirPath.substringAfterLast('/')
            val dirName = try {
                java.net.URLDecoder.decode(rawName, "UTF-8")
            } catch (_: Exception) {
                rawName
            }.ifBlank { "相册 ${index + 1}" }

            // 判断父目录
            val parentPath = dirPath.substringBeforeLast('/')
            val parentAlbumId = if (parentPath.isNotBlank() && parentPath != rootUriString) {
                albumMap[parentPath]
            } else {
                null
            }

            val albumEntity = AlbumEntity(
                name = dirName,
                directoryPath = dirPath,
                repositoryId = repositoryId,
                parentAlbumId = parentAlbumId,
                coverImagePath = null,
                sortOrder = null,
                imageCount = 0
            )

            val albumId = albumDao.insert(albumEntity)
            albumMap[dirPath] = albumId
        }

        return albumMap
    }

    /**
     * 更新所有相册的图片数量统计
     */
    private suspend fun updateAlbumImageCounts() {
        val allAlbums = albumDao.getAllAlbumsList()
        for (album in allAlbums) {
            val count = imageDao.getImageCountByAlbum(album.id)
            if (count != album.imageCount) {
                albumDao.updateImageCount(album.id, count)
            }
        }
    }

    companion object {
        private const val TAG = "ScanImages"
    }
}
