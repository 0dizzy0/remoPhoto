package com.remophoto.domain.usecase

import com.remophoto.data.local.entity.ImageEntity
import com.remophoto.domain.model.SortOrder

/**
 * 排序图片用例
 *
 * 根据指定排序策略对图片列表排序。
 * Phase 1 中接入数据库端排序（性能更好）。
 */
class SortImagesUseCase {

    /**
     * 对图片列表排序
     *
     * @param images 待排序的图片列表
     * @param sortOrder 排序策略
     * @return 排序后的图片列表
     */
    operator fun invoke(
        images: List<ImageEntity>,
        sortOrder: SortOrder
    ): List<ImageEntity> {
        return when (sortOrder) {
            SortOrder.DATE_MODIFIED_ASC -> images.sortedBy { it.lastModified }
            SortOrder.DATE_MODIFIED_DESC -> images.sortedByDescending { it.lastModified }
            SortOrder.NAME_ASC -> images.sortedBy { it.fileName.lowercase() }
            SortOrder.NAME_DESC -> images.sortedByDescending { it.fileName.lowercase() }
            SortOrder.SIZE_ASC -> images.sortedBy { it.fileSize }
            SortOrder.SIZE_DESC -> images.sortedByDescending { it.fileSize }
        }
    }
}
