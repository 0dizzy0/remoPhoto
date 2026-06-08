package com.remophoto.domain.usecase

import com.remophoto.data.local.entity.AlbumEntity
import com.remophoto.data.repository.AlbumRepository
import com.remophoto.util.Constants

/**
 * 自动创建相册用例（骨架）
 *
 * 根据目录结构自动创建相册层级。
 * Phase 1 中实现完整的父子关系、封面选取和深度限制。
 */
class CreateAlbumsUseCase(private val albumRepository: AlbumRepository) {

    /**
     * 从目录路径创建相册实体
     *
     * @param directoryPath 目录路径
     * @param repositoryId 所属仓库 ID
     * @param parentAlbumId 父相册 ID
     * @return 创建的相册 ID
     */
    suspend operator fun invoke(
        directoryPath: String,
        repositoryId: Long,
        parentAlbumId: Long? = null,
        depth: Int = 0
    ): Long? {
        // 深度超过限制则合并到父相册
        val effectiveDepth = if (depth >= Constants.MAX_ALBUM_DEPTH) {
            return null // 跳过，不创建更深层相册
        } else {
            depth
        }

        val albumEntity = AlbumEntity(
            name = directoryPath.substringAfterLast('/'),
            directoryPath = directoryPath,
            repositoryId = repositoryId,
            parentAlbumId = parentAlbumId,
            imageCount = 0
        )

        return albumRepository.insert(albumEntity)
    }
}
