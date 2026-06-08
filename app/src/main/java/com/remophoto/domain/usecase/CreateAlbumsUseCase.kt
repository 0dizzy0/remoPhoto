package com.remophoto.domain.usecase

import com.remophoto.data.local.dao.AlbumDao
import com.remophoto.data.local.dao.ImageDao
import com.remophoto.data.local.entity.AlbumEntity
import com.remophoto.data.local.entity.ImageEntity
import com.remophoto.util.Constants

/**
 * 自动创建相册用例
 *
 * 根据扫描到的图片文件的目录结构，自动创建多层级相册。
 *
 * 规则：
 * - 每个子目录 → 一个相册
 * - 子目录嵌套 → 父子相册关系（通过 parentAlbumId）
 * - 最大深度 3 层（Constants.MAX_ALBUM_DEPTH），超过则合并到父级
 * - 根目录下的散落图片（不属任何子目录）→ 归入根相册
 */
class CreateAlbumsUseCase(
    private val albumDao: AlbumDao,
    private val imageDao: ImageDao
) {

    /**
     * 从图片索引列表自动创建相册层级
     *
     * @param repositoryId 所属仓库 ID
     * @param rootUriString 根目录 URI 字符串
     * @param rootName 根目录名称
     * @param images 扫描到的图片实体列表
     * @return 目录路径 → 相册 ID 的映射
     */
    suspend fun createAlbumsFromImages(
        repositoryId: Long,
        rootUriString: String,
        rootName: String,
        images: List<ImageEntity>
    ): Map<String, Long> {
        // 按父目录路径分组图片
        val imagesByDir: Map<String, List<ImageEntity>> = images.groupBy { image ->
            // 从 filePath URI 中提取父目录路径
            val filePath = image.filePath
            filePath.substringBeforeLast('/')
        }

        // 收集所有需要创建相册的目录路径
        val allDirPaths = imagesByDir.keys.toSet()

        // 过滤掉根目录（rootUriString）
        val subDirPaths = allDirPaths.filter { it != rootUriString && it.isNotBlank() }

        // 按目录层级排序（浅层优先，确保父相册先创建）
        val sortedPaths = subDirPaths.sortedBy { path ->
            path.removePrefix(rootUriString).count { it == '/' }
        }

        // 创建相册映射
        val albumIdMap = mutableMapOf<String, Long>()

        // 先创建根相册（如果根目录有散落图片）
        val rootImages = imagesByDir[rootUriString]
        if (!rootImages.isNullOrEmpty()) {
            val rootAlbum = AlbumEntity(
                name = rootName,
                directoryPath = rootUriString,
                repositoryId = repositoryId,
                parentAlbumId = null,
                coverImagePath = null,
                sortOrder = null,
                imageCount = rootImages.size
            )
            val rootAlbumId = albumDao.insert(rootAlbum)
            albumIdMap[rootUriString] = rootAlbumId
        }

        // 为每个子目录创建相册
        for (dirPath in sortedPaths) {
            // 计算深度
            val relativeDepth = calculateDepth(dirPath, rootUriString)

            // 超过最大深度则跳过（合并到父级）
            if (relativeDepth > Constants.MAX_ALBUM_DEPTH) {
                continue
            }

            // 确定父相册
            val parentPath = dirPath.substringBeforeLast('/')
            val parentAlbumId: Long? = if (parentPath == rootUriString) {
                // 父级是根目录
                albumIdMap[rootUriString]
            } else {
                // 父级是已创建的子目录相册
                albumIdMap[parentPath]
            }

            val dirName = dirPath.substringAfterLast('/').ifBlank { "未命名相册" }
            val dirImages = imagesByDir[dirPath] ?: emptyList()

            val albumEntity = AlbumEntity(
                name = dirName,
                directoryPath = dirPath,
                repositoryId = repositoryId,
                parentAlbumId = parentAlbumId,
                coverImagePath = null,
                sortOrder = null,
                imageCount = dirImages.size
            )

            val albumId = albumDao.insert(albumEntity)
            albumIdMap[dirPath] = albumId

            // 更新图片的 albumId
            for (image in dirImages) {
                imageDao.upsertAll(listOf(image.copy(albumId = albumId)))
            }
        }

        // 更新根目录散落图片的 albumId
        if (rootImages != null && albumIdMap.containsKey(rootUriString)) {
            val rootAlbumId = albumIdMap[rootUriString]!!
            for (image in rootImages) {
                imageDao.upsertAll(listOf(image.copy(albumId = rootAlbumId)))
            }
        }

        return albumIdMap
    }

    /**
     * 从目录路径创建单个相册（用于手动添加相册）
     *
     * @param directoryPath 目录路径
     * @param repositoryId 所属仓库 ID
     * @param parentAlbumId 父相册 ID
     * @param depth 当前深度（从根目录计算）
     * @return 创建的相册 ID，若超过深度限制则返回 null
     */
    suspend fun createSingleAlbum(
        directoryPath: String,
        repositoryId: Long,
        parentAlbumId: Long? = null,
        depth: Int = 0
    ): Long? {
        // 深度超过限制则跳过
        if (depth > Constants.MAX_ALBUM_DEPTH) {
            return null
        }

        val albumEntity = AlbumEntity(
            name = directoryPath.substringAfterLast('/').ifBlank { "未命名" },
            directoryPath = directoryPath,
            repositoryId = repositoryId,
            parentAlbumId = parentAlbumId,
            imageCount = 0
        )

        return albumDao.insert(albumEntity)
    }

    /**
     * 计算目录相对于根目录的深度
     *
     * 例如：/root/a/b → depth=2
     */
    private fun calculateDepth(dirPath: String, rootUriString: String): Int {
        val relative = dirPath.removePrefix(rootUriString)
            .trimStart('/')
        if (relative.isEmpty()) return 0
        return relative.count { it == '/' } + 1
    }
}
