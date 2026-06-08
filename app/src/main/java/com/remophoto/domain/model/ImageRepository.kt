package com.remophoto.domain.model

/**
 * 图片仓库领域模型（UI 层使用）
 *
 * 管理一个根图片仓库的元信息。
 */
data class ImageRepository(
    val id: Long = 0,
    val path: String,
    val name: String,
    val addedTime: Long,
    val lastScanTime: Long = 0,
    val imageCount: Int = 0
) {
    /** 最后扫描时间（人类可读） */
    val lastScanTimeDisplay: String
        get() = if (lastScanTime == 0L) "未扫描"
        else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(lastScanTime))
}
