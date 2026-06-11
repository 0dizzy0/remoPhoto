package com.remophoto.domain.model

import androidx.compose.runtime.Immutable

/**
 * 相册领域模型（UI 层使用）
 */
@Immutable
data class Album(
    val id: Long = 0,
    val name: String,
    val directoryPath: String,
    val repositoryId: Long,
    val parentAlbumId: Long? = null,
    val coverImagePath: String? = null,
    val sortOrder: SortOrder? = null,
    val imageCount: Int = 0,
    /** 子相册列表（多层级展示） */
    val children: List<Album> = emptyList(),
    /** 嵌套深度（0 = 根级） */
    val depth: Int = 0
) {
    /** 是否为根级相册 */
    val isRoot: Boolean get() = parentAlbumId == null

    /** 有效排序方式（单相册设置 > 全局设置 > 默认） */
    fun effectiveSortOrder(globalSortOrder: SortOrder): SortOrder =
        sortOrder ?: globalSortOrder
}
