package com.remophoto.domain.usecase

import com.remophoto.data.local.dao.AlbumDao
import com.remophoto.data.local.dao.ImageDao

/**
 * 相册封面管理器
 *
 * 封装封面选取策略：
 * - 默认取该目录下第一张图片（按文件名升序排列）
 * - 支持手动设置封面（自定义封面路径持久化）
 * - 原封面被删除后自动选取新封面
 *
 * 规则：
 * - 若已设置自定义封面 → 保持不变
 * - 否则 → 自动选取文件名字母序第一张
 * - 若当前封面图片被删除 → 自动重新选取
 */
class AlbumCoverManager(
    private val albumDao: AlbumDao,
    private val imageDao: ImageDao
) {

    /**
     * 自动为所有相册选取封面
     *
     * 扫描完成后批量调用。
     */
    suspend fun autoSelectAllCovers() {
        val allAlbums = albumDao.getAllAlbumsList()
        for (album in allAlbums) {
            // 如果已有自定义封面，跳过
            if (album.coverImagePath != null) continue

            autoSelectCover(album.id)
        }
    }

    /**
     * 自动为单个相册选取封面
     *
     * @param albumId 相册 ID
     * @return 封面图片路径，若相册为空则返回 null
     */
    suspend fun autoSelectCover(albumId: Long): String? {
        val firstImage = imageDao.getFirstImageByAlbum(albumId)
        return if (firstImage != null) {
            albumDao.updateCoverImage(albumId, firstImage.filePath)
            firstImage.filePath
        } else {
            null
        }
    }

    /**
     * 手动设置相册封面
     *
     * @param albumId 相册 ID
     * @param imagePath 封面图片路径
     */
    suspend fun setCustomCover(albumId: Long, imagePath: String) {
        albumDao.updateCoverImage(albumId, imagePath)
    }

    /**
     * 清除自定义封面（恢复自动选取）
     *
     * @param albumId 相册 ID
     */
    suspend fun clearCustomCover(albumId: Long) {
        albumDao.updateCoverImage(albumId, null)
        autoSelectCover(albumId)
    }

    /**
     * 检查并更新封面（当图片被删除后调用）
     *
     * 如果当前封面路径对应的图片已不存在，自动选取新封面。
     *
     * @param albumId 相册 ID
     * @param deletedImagePath 被删除的图片路径
     */
    suspend fun updateCoverIfNeeded(albumId: Long, deletedImagePath: String?) {
        val album = albumDao.getAlbumById(albumId) ?: return

        // 如果被删除的图片正是当前封面
        if (deletedImagePath != null && album.coverImagePath == deletedImagePath) {
            autoSelectCover(albumId)
        }

        // 如果相册为空且封面路径不为空，清除封面
        val imageCount = imageDao.getImageCountByAlbum(albumId)
        if (imageCount == 0 && album.coverImagePath != null) {
            albumDao.updateCoverImage(albumId, null)
        }
    }
}
