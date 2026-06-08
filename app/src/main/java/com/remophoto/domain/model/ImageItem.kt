package com.remophoto.domain.model

/**
 * 图片领域模型（UI 层使用）
 */
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
