package com.remophoto.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 相册实体（支持多层级嵌套）
 */
@Entity(
    tableName = "albums",
    indices = [
        Index("parent_album_id"),
        Index("repository_id"),
        Index("directory_path")
    ]
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 相册名（目录名） */
    val name: String,

    /** 对应的目录路径 */
    @ColumnInfo(name = "directory_path")
    val directoryPath: String,

    /** 所属仓库 ID */
    @ColumnInfo(name = "repository_id")
    val repositoryId: Long,

    /** 父相册 ID（null = 根级相册） */
    @ColumnInfo(name = "parent_album_id")
    val parentAlbumId: Long? = null,

    /** 封面图路径（null = 自动选取第一张） */
    @ColumnInfo(name = "cover_image_path")
    val coverImagePath: String? = null,

    /** 排序方式名称（null = 使用全局设置） */
    @ColumnInfo(name = "sort_order")
    val sortOrder: String? = null,

    /** 图片数量（仅本目录，不含子相册） */
    @ColumnInfo(name = "image_count")
    val imageCount: Int = 0
)
