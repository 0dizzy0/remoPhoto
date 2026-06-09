package com.remophoto.domain.usecase

import com.remophoto.data.local.dao.AlbumDao
import com.remophoto.data.local.dao.ImageDao
import com.remophoto.util.AppLogger

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
        AppLogger.i(TAG, "开始为 ${allAlbums.size} 个相册自动选取封面")

        var selected = 0
        var skipped = 0
        var empty = 0

        for (album in allAlbums) {
            // 如果已有自定义封面，跳过
            if (album.coverImagePath != null) {
                skipped++
                AppLogger.d(TAG, "相册 \"${album.name}\" (id=$album.id) 已有自定义封面，跳过")
                continue
            }

            val coverPath = autoSelectCover(album.id)
            if (coverPath != null) {
                selected++
            } else {
                empty++
            }
        }

        AppLogger.i(TAG, "封面选取完成: 已设置=$selected, 已跳过(自定义)=$skipped, 空相册=$empty")
        if (empty > 0) {
            AppLogger.w(TAG, "$empty 个相册无图片，封面留空")
        }
    }

    /**
     * 自动为单个相册选取封面
     *
     * @param albumId 相册 ID
     * @return 封面图片路径，若相册为空则返回 null
     */
    suspend fun autoSelectCover(albumId: Long): String? {
        val album = albumDao.getAlbumById(albumId)
        val albumName = album?.name ?: "unknown"
        val imageCount = imageDao.getImageCountByAlbum(albumId)

        AppLogger.d(TAG, "选取封面: albumId=$albumId, name=\"$albumName\", imageCount=$imageCount")

        val firstImage = imageDao.getFirstImageByAlbum(albumId)
        return if (firstImage != null) {
            // 验证封面图片确实属于该相册
            if (firstImage.albumId != albumId && firstImage.albumId != 0L) {
                AppLogger.e(TAG,
                    "封面图片 albumId 不匹配！选中图片 albumId=${firstImage.albumId}, " +
                    "目标相册 albumId=$albumId, 图片路径=${firstImage.filePath}"
                )
            }

            albumDao.updateCoverImage(albumId, firstImage.filePath)
            AppLogger.i(TAG,
                "封面已设置: albumId=$albumId, name=\"$albumName\", " +
                "coverPath=${firstImage.filePath}, fileName=${firstImage.fileName}"
            )
            firstImage.filePath
        } else {
            AppLogger.w(TAG, "相册 \"$albumName\" (id=$albumId) 中无图片，封面留空")
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
        AppLogger.i(TAG, "手动设置封面: albumId=$albumId, path=$imagePath")
        albumDao.updateCoverImage(albumId, imagePath)
    }

    /**
     * 清除自定义封面（恢复自动选取）
     *
     * @param albumId 相册 ID
     */
    suspend fun clearCustomCover(albumId: Long) {
        AppLogger.i(TAG, "清除自定义封面: albumId=$albumId, 恢复自动选取")
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
            AppLogger.i(TAG, "封面图片被删除，重新选取: albumId=$albumId, deletedPath=$deletedImagePath")
            autoSelectCover(albumId)
        }

        // 如果相册为空且封面路径不为空，清除封面
        val imageCount = imageDao.getImageCountByAlbum(albumId)
        if (imageCount == 0 && album.coverImagePath != null) {
            AppLogger.i(TAG, "相册已空，清除封面: albumId=$albumId")
            albumDao.updateCoverImage(albumId, null)
        }
    }

    /**
     * 验证封面路径对应的图片是否属于指定相册
     *
     * 用于调试封面显示问题。返回 false 表示封面可能加载了错误相册的图片。
     *
     * @param albumId 相册 ID
     * @return true=封面图片属于该相册或相册无封面, false=封面图片不属于该相册
     */
    suspend fun validateCoverBelongsToAlbum(albumId: Long): Boolean {
        val album = albumDao.getAlbumById(albumId) ?: return false
        val coverPath = album.coverImagePath ?: return true  // 无封面，合法

        val coverImage = imageDao.getImageByPath(coverPath)
        if (coverImage == null) {
            AppLogger.w(TAG, "封面验证失败: 封面图片不存在, albumId=$albumId, coverPath=$coverPath")
            return false
        }
        if (coverImage.albumId != albumId) {
            AppLogger.e(TAG,
                "封面验证失败: 封面图片属于其他相册! " +
                "coverImage.albumId=${coverImage.albumId}, targetAlbumId=$albumId, path=$coverPath"
            )
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "AlbumCover"
    }
}
