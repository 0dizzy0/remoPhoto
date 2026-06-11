package com.remophoto.domain.model

import androidx.compose.runtime.Immutable
import com.remophoto.data.local.entity.ImageEntity

/**
 * 图片领域模型（UI 层使用）
 */
@Immutable
data class ImageItem(
    val id: Long = 0,
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Long,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0,
    val albumId: Long,
    val repositoryId: Long
) {
    companion object {
        fun fromEntity(entity: ImageEntity): ImageItem = ImageItem(
            id = entity.id,
            filePath = entity.filePath,
            fileName = entity.fileName,
            fileSize = entity.fileSize,
            lastModified = entity.lastModified,
            mimeType = entity.mimeType,
            width = entity.width,
            height = entity.height,
            albumId = entity.albumId,
            repositoryId = entity.repositoryId
        )
    }
    /** 是否为动图（GIF / WebP 动图） */
    val isAnimated: Boolean
        get() = mimeType in setOf("image/gif", "image/webp")

    /** 文件大小（人类可读） */
    val displaySize: String
        get() = when {
            fileSize < 1024 -> "${fileSize}B"
            fileSize < 1024 * 1024 -> "${fileSize / 1024}KB"
            fileSize < 1024 * 1024 * 1024 -> "${"%.1f".format(fileSize / (1024.0 * 1024.0))}MB"
            else -> "${"%.2f".format(fileSize / (1024.0 * 1024.0 * 1024.0))}GB"
        }
}
