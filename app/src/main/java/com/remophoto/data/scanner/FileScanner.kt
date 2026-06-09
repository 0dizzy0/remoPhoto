package com.remophoto.data.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.remophoto.data.local.dao.ImageDao
import com.remophoto.data.local.entity.ImageEntity
import com.remophoto.util.AppLogger
import com.remophoto.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件系统扫描器
 *
 * 递归遍历根仓库目录，识别图片文件并提取元数据，写入 Room 数据库。
 *
 * 支持：
 * - 全量扫描：首次添加仓库时使用
 * - 增量扫描：仅处理修改时间晚于 [lastScanTime] 的文件
 * - 进度回调：实时报告扫描进度
 * - 分批写入：每 [Constants.DB_BATCH_SIZE] 条图片执行一次数据库事务
 *
 * @param context Application Context
 */
class FileScanner(val context: Context) {

    /**
     * 扫描结果数据类
     */
    data class ScanResult(
        /** 发现的图片实体列表 */
        val images: List<ImageEntity>,
        /** 发现的子目录 URI 列表 */
        val subDirectories: List<Uri>,
        /** 扫描的文件总数 */
        val totalFiles: Int,
        /** 扫描耗时（毫秒） */
        val elapsedMs: Long
    )

    /**
     * 扫描进度回调
     *
     * @param current 当前已处理的文件数
     * @param total 预估总文件数（-1 表示未知）
     * @param phase 当前阶段描述
     */
    data class ScanProgress(
        val current: Int,
        val total: Int,
        val phase: String
    )

    // ===== 全量扫描 =====

    /**
     * 全量扫描指定目录
     *
     * @param rootUri 根目录 URI（SAF 授权后的 content:// URI）
     * @param repositoryId 所属仓库 ID
     * @param albumIdMap 目录路径 → 相册 ID 的映射（由 AlbumAutoCreator 创建后传入）
     * @param imageDao 图片 DAO（用于分批写入）
     * @param onProgress 进度回调
     * @return 扫描结果
     */
    suspend fun fullScan(
        rootUri: Uri,
        repositoryId: Long,
        albumIdMap: Map<String, Long>,
        imageDao: ImageDao,
        onProgress: (ScanProgress) -> Unit = {}
    ): ScanResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val images = mutableListOf<ImageEntity>()

        val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
        if (rootDoc == null) {
            return@withContext ScanResult(emptyList(), emptyList(), 0, 0)
        }

        // 第一遍：递归收集所有图片 URI
        onProgress(ScanProgress(0, -1, "正在遍历目录…"))
        val imageFiles = mutableListOf<ImageFileInfo>()
        val directories = mutableListOf<Uri>()
        collectImageFiles(rootDoc, rootUri.toString(), imageFiles, directories, depth = 0)

        val totalImages = imageFiles.size
        onProgress(ScanProgress(0, totalImages, "正在提取元数据…"))

        // 第二遍：提取元数据并构建 ImageEntity
        var processedCount = 0
        var batchCount = 0

        for (fileInfo in imageFiles) {
            val metadata = extractFullMetadata(fileInfo.uri, fileInfo.fileName)
            if (metadata != null) {
                // 确定所属相册：按目录路径匹配
                val albumId = findAlbumId(fileInfo.parentPath, albumIdMap)

                val entity = ImageEntity(
                    filePath = fileInfo.uri.toString(),
                    fileName = metadata.fileName,
                    fileSize = metadata.fileSize,
                    lastModified = metadata.lastModified,
                    mimeType = metadata.mimeType,
                    width = 0,  // 宽高延迟提取（解码开销大）
                    height = 0,
                    albumId = albumId,
                    repositoryId = repositoryId
                )
                images.add(entity)
            }

            processedCount++

            // 分批写入数据库
            if (images.size >= Constants.DB_BATCH_SIZE) {
                imageDao.upsertAll(images.toList())
                batchCount += images.size
                images.clear()
                onProgress(ScanProgress(processedCount, totalImages, "正在写入索引 ($processedCount/$totalImages)…"))
            }
        }

        // 写入剩余数据
        if (images.isNotEmpty()) {
            imageDao.upsertAll(images.toList())
            batchCount += images.size
            onProgress(ScanProgress(totalImages, totalImages, "索引写入完成"))
        }

        val elapsed = System.currentTimeMillis() - startTime
        ScanResult(
            images = images.toList(),
            subDirectories = directories,
            totalFiles = totalImages,
            elapsedMs = elapsed
        )
    }

    // ===== 增量扫描 =====

    /**
     * 增量扫描：仅处理修改时间晚于 [lastScanTime] 的文件
     *
     * @param rootUri 根目录 URI
     * @param repositoryId 所属仓库 ID
     * @param albumIdMap 目录路径 → 相册 ID 映射
     * @param lastScanTime 上次扫描时间戳（毫秒），仅处理修改时间大于此值的文件
     * @param imageDao 图片 DAO
     * @param onProgress 进度回调
     * @return 新增/更新的图片数量
     */
    suspend fun incrementalScan(
        rootUri: Uri,
        repositoryId: Long,
        albumIdMap: Map<String, Long>,
        lastScanTime: Long,
        imageDao: ImageDao,
        onProgress: (ScanProgress) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var newOrUpdatedCount = 0

        val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext 0

        onProgress(ScanProgress(0, -1, "正在增量扫描…"))

        val imageFiles = mutableListOf<ImageFileInfo>()
        val directories = mutableListOf<Uri>()
        collectImageFiles(rootDoc, rootUri.toString(), imageFiles, directories, depth = 0)

        val batchEntities = mutableListOf<ImageEntity>()

        for (fileInfo in imageFiles) {
            val metadata = extractFullMetadata(fileInfo.uri, fileInfo.fileName) ?: continue

            // 仅处理修改时间晚于 lastScanTime 的文件
            if (metadata.lastModified <= lastScanTime) continue

            val albumId = findAlbumId(fileInfo.parentPath, albumIdMap)

            val entity = ImageEntity(
                filePath = fileInfo.uri.toString(),
                fileName = metadata.fileName,
                fileSize = metadata.fileSize,
                lastModified = metadata.lastModified,
                mimeType = metadata.mimeType,
                width = 0,
                height = 0,
                albumId = albumId,
                repositoryId = repositoryId
            )
            batchEntities.add(entity)
            newOrUpdatedCount++

            if (batchEntities.size >= Constants.DB_BATCH_SIZE) {
                imageDao.upsertAll(batchEntities.toList())
                batchEntities.clear()
                onProgress(ScanProgress(newOrUpdatedCount, -1, "更新 $newOrUpdatedCount 张图片…"))
            }
        }

        // 写入剩余
        if (batchEntities.isNotEmpty()) {
            imageDao.upsertAll(batchEntities.toList())
        }

        onProgress(ScanProgress(newOrUpdatedCount, newOrUpdatedCount, "增量扫描完成"))

        newOrUpdatedCount
    }

    // ===== 内部辅助方法 =====

    /**
     * 图片文件信息（元数据提取前的中间数据）
     *
     * 暴露给 ScanImagesUseCase 用于单次遍历扫描优化。
     */
    data class ImageFileInfo(
        val uri: Uri,
        val fileName: String,
        val parentPath: String  // 父目录的 URI 字符串
    )

    /**
     * 递归收集目录下的所有图片文件和子目录
     *
     * 单次遍历同时收集图片文件和子目录 URI。
     * 供 ScanImagesUseCase 的单遍扫描优化使用。
     */
    fun collectImageFiles(
        directory: DocumentFile,
        directoryPath: String,
        images: MutableList<ImageFileInfo>,
        directories: MutableList<Uri>,
        depth: Int,
        onProgress: ((Int) -> Unit)? = null
    ) {
        // 目录深度保护（最深 10 层）
        if (depth > 10) return

        val children = try {
            directory.listFiles()
        } catch (e: Exception) {
            return
        }

        for (child in children) {
            when {
                child.isDirectory -> {
                    directories.add(child.uri)
                    collectImageFiles(child, child.uri.toString(), images, directories, depth + 1, onProgress)
                }
                child.isFile && isImageFile(child) -> {
                    images.add(
                        ImageFileInfo(
                            uri = child.uri,
                            fileName = child.name ?: "unknown",
                            parentPath = directoryPath
                        )
                    )
                    onProgress?.invoke(images.size + directories.size)
                }
            }
        }
    }

    /**
     * 判断文件是否为支持的图片格式
     */
    private fun isImageFile(file: DocumentFile): Boolean {
        val name = file.name ?: return false
        // 跳过隐藏文件（以 . 开头，如 .nomedia.jpg）
        if (name.startsWith(".")) return false
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in Constants.SUPPORTED_IMAGE_EXTENSIONS) return false

        val mimeType = file.type
        return mimeType == null || mimeType.startsWith("image/")
    }

    /**
     * 提取完整文件元数据
     */
    private fun extractFullMetadata(uri: Uri, fallbackName: String): FullMetadata? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)

                    val fileName = if (nameIdx >= 0) cursor.getString(nameIdx) else fallbackName
                    val fileSize = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L

                    // 尝试获取修改时间
                    var lastModified = 0L
                    val lastModifiedIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (lastModifiedIdx >= 0) {
                        lastModified = cursor.getLong(lastModifiedIdx)
                    }
                    // 如果文档提供者不支持，回退到当前时间
                    if (lastModified == 0L) {
                        lastModified = System.currentTimeMillis()
                    }

                    // MIME 类型
                    var mimeType = "image/*"
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    if (mimeIdx >= 0) {
                        mimeType = cursor.getString(mimeIdx) ?: "image/*"
                    }

                    FullMetadata(
                        fileName = fileName ?: fallbackName,
                        fileSize = if (fileSize > 0) fileSize else 0L,
                        lastModified = lastModified,
                        mimeType = mimeType
                    )
                } else null
            }
        } catch (e: Exception) {
            // 回退到基本信息
            FullMetadata(
                fileName = fallbackName,
                fileSize = 0L,
                lastModified = System.currentTimeMillis(),
                mimeType = "image/*"
            )
        }
    }

    /**
     * 根据图片的父目录路径找到对应的相册 ID
     *
     * 路径匹配策略：
     * 1. 精确匹配
     * 2. 规范化匹配（去除尾部 /、URL 解码后比较）
     * 3. 模糊匹配（仅按路径末尾匹配）
     */
    private fun findAlbumId(parentPath: String, albumIdMap: Map<String, Long>): Long {
        // 1. 精确匹配
        albumIdMap[parentPath]?.let { return it }

        // 2. 规范化匹配：去除尾部斜杠、URL 解码
        val normalizedPath = normalizePath(parentPath)
        for ((key, value) in albumIdMap) {
            if (normalizePath(key) == normalizedPath) {
                return value
            }
        }

        // 3. 模糊匹配：尝试按路径最后一段（目录名）匹配
        //   仅当 albumIdMap 中只有一个匹配时使用
        val lastSegment = normalizedPath.substringAfterLast('/')
        if (lastSegment.isNotBlank()) {
            val candidates = albumIdMap.filter { (key, _) ->
                normalizePath(key).endsWith("/$lastSegment")
            }
            if (candidates.size == 1) {
                return candidates.values.first()
            }
        }

        // 匹配失败 — 记录 WARN 日志
        if (albumIdMap.isNotEmpty()) {
            val sampleKeys = albumIdMap.keys.take(3).joinToString(" | ")
            AppLogger.w("FileScanner",
                "图片父目录无法匹配任何相册: parentPath=$parentPath, " +
                "normalized=$normalizedPath, sampleAlbumPaths=[$sampleKeys]"
            )
        }

        // 返回 0（不属于任何相册）
        return 0L
    }

    /**
     * 规范化目录路径：去除尾部 /、URL 解码
     */
    private fun normalizePath(path: String): String {
        var normalized = path.trimEnd('/')
        // 尝试 URL 解码（处理 SAF URI 中的 % 编码）
        try {
            val decoded = java.net.URLDecoder.decode(normalized, "UTF-8")
            // 仅在解码结果与原值不同时使用解码值（避免双重解码）
            if (decoded != normalized) {
                normalized = decoded
            }
        } catch (_: Exception) {
            // 解码失败，保持原路径
        }
        return normalized
    }

    /**
     * 完整文件元数据
     */
    data class FullMetadata(
        val fileName: String,
        val fileSize: Long,
        val lastModified: Long,
        val mimeType: String
    )

    /**
     * 遍历根目录，同时收集图片文件和子目录（单次遍历）
     *
     * @param rootUri 根目录 URI
     * @param onProgress 进度回调（已遍历的文件数）
     * @return 图片文件信息列表和子目录 URI 列表。
     *         返回 null 表示根目录无法访问（权限问题）。
     */
    suspend fun collectDirectoriesAndImages(
        rootUri: Uri,
        onProgress: ((Int) -> Unit)? = null
    ): Pair<List<ImageFileInfo>, List<Uri>>? = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext null
        val imageFiles = mutableListOf<ImageFileInfo>()
        val directories = mutableListOf<Uri>()
        // 每发现 50 个文件报告一次进度
        var fileCount = 0
        collectImageFiles(
            rootDoc, rootUri.toString(), imageFiles, directories, depth = 0,
            onProgress = { count ->
                fileCount = count
                onProgress?.invoke(count)
            }
        )
        Pair(imageFiles, directories)
    }

    /**
     * 构建 ImageEntity 列表（仅提取元数据，不写入数据库）
     *
     * 用于安全的重扫流程：先在内存中构建全部实体，再在事务中原子替换旧数据。
     */
    suspend fun buildImageEntities(
        imageFiles: List<ImageFileInfo>,
        repositoryId: Long,
        albumIdMap: Map<String, Long>,
        onProgress: (ScanProgress) -> Unit = {}
    ): List<ImageEntity> = withContext(Dispatchers.IO) {
        val totalFiles = imageFiles.size
        val entities = mutableListOf<ImageEntity>()

        onProgress(ScanProgress(0, totalFiles, "正在提取元数据…"))

        for ((index, fileInfo) in imageFiles.withIndex()) {
            val metadata = extractFullMetadata(fileInfo.uri, fileInfo.fileName)
            if (metadata != null) {
                val albumId = findAlbumId(fileInfo.parentPath, albumIdMap)
                entities.add(
                    ImageEntity(
                        filePath = fileInfo.uri.toString(),
                        fileName = metadata.fileName,
                        fileSize = metadata.fileSize,
                        lastModified = metadata.lastModified,
                        mimeType = metadata.mimeType,
                        width = 0,
                        height = 0,
                        albumId = albumId,
                        repositoryId = repositoryId
                    )
                )
            }
            if (index % 100 == 0) {
                onProgress(ScanProgress(index + 1, totalFiles, "提取元数据 ${index + 1}/$totalFiles"))
            }
        }

        onProgress(ScanProgress(totalFiles, totalFiles, "元数据提取完成（${entities.size} 张）"))
        entities
    }

    /**
     * 处理已收集的图片文件列表，提取元数据并分批写入数据库
     *
     * 与 [fullScan] 不同，此方法只处理元数据和写入，不遍历文件系统。
     * 配合 [collectImageFiles] 实现单次遍历扫描优化。
     *
     * @param imageFiles 已收集的图片文件信息列表
     * @param repositoryId 所属仓库 ID
     * @param albumIdMap 目录路径 → 相册 ID 映射
     * @param imageDao 图片 DAO
     * @param onProgress 进度回调
     * @return 成功写入的图片总数
     */
    suspend fun processImages(
        imageFiles: List<ImageFileInfo>,
        repositoryId: Long,
        albumIdMap: Map<String, Long>,
        imageDao: ImageDao,
        onProgress: (ScanProgress) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        val totalFiles = imageFiles.size
        var processedCount = 0
        var writtenCount = 0
        val batch = mutableListOf<ImageEntity>()

        onProgress(ScanProgress(0, totalFiles, "正在提取图片索引 (0/$totalFiles)…"))

        for (fileInfo in imageFiles) {
            val metadata = extractFullMetadata(fileInfo.uri, fileInfo.fileName)
            if (metadata != null) {
                val albumId = findAlbumId(fileInfo.parentPath, albumIdMap)

                val entity = ImageEntity(
                    filePath = fileInfo.uri.toString(),
                    fileName = metadata.fileName,
                    fileSize = metadata.fileSize,
                    lastModified = metadata.lastModified,
                    mimeType = metadata.mimeType,
                    width = 0,
                    height = 0,
                    albumId = albumId,
                    repositoryId = repositoryId
                )
                batch.add(entity)
            }

            processedCount++

            if (batch.size >= Constants.DB_BATCH_SIZE) {
                imageDao.upsertAll(batch.toList())
                writtenCount += batch.size
                batch.clear()
                onProgress(ScanProgress(processedCount, totalFiles, "正在写入索引 ($processedCount/$totalFiles)…"))
            }
        }

        // 写入剩余
        if (batch.isNotEmpty()) {
            imageDao.upsertAll(batch.toList())
            writtenCount += batch.size
        }

        onProgress(ScanProgress(totalFiles, totalFiles, "索引写入完成 ($writtenCount 张图片)"))
        writtenCount
    }

    // ===== 旧版 API（兼容现有代码） =====

    /**
     * 扫描指定目录（简化版，返回 URI 列表）
     *
     * 保留此方法以兼容现有 ScanImagesUseCase 的旧代码。
     * 新代码请使用 [fullScan]。
     */
    @Deprecated("使用 fullScan() 替代，该方法直接返回 ImageEntity 列表并写入数据库")
    suspend fun scan(rootUri: Uri): ScanResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val images = mutableListOf<Uri>()
        val directories = mutableListOf<Uri>()

        val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
        if (rootDoc != null) {
            scanDirectory(rootDoc, images, directories, depth = 0)
        }

        val elapsed = System.currentTimeMillis() - startTime
        ScanResult(
            images = emptyList(),
            subDirectories = directories,
            totalFiles = images.size + directories.size,
            elapsedMs = elapsed
        )
    }

    /**
     * 递归扫描单个目录（旧版辅助方法）
     */
    private fun scanDirectory(
        directory: DocumentFile,
        images: MutableList<Uri>,
        directories: MutableList<Uri>,
        depth: Int
    ) {
        if (depth > 10) return

        val children = directory.listFiles()
        for (child in children) {
            when {
                child.isDirectory -> {
                    directories.add(child.uri)
                    scanDirectory(child, images, directories, depth + 1)
                }
                child.isFile && isImageFile(child) -> {
                    images.add(child.uri)
                }
            }
        }
    }

    /**
     * 从 content:// URI 提取基本文件元数据（旧版方法，兼容现有代码）
     */
    fun extractMetadata(uri: Uri): FileMetadata? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)

                    FileMetadata(
                        uri = uri,
                        fileName = if (nameIdx >= 0) cursor.getString(nameIdx) else "unknown",
                        fileSize = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    )
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 文件元数据（旧版）
     */
    data class FileMetadata(
        val uri: Uri,
        val fileName: String,
        val fileSize: Long
    )
}
