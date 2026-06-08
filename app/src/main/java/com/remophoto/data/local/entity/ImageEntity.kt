package com.remophoto.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 图片索引实体
 */
@Entity(
    tableName = "images",
    indices = [
        Index("album_id"),
        Index("repository_id"),
        Index("last_modified")
    ]
)
data class ImageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 文件绝对路径 */
    @ColumnInfo(name = "file_path")
    val filePath: String,

    /** 文件名（含扩展名） */
    @ColumnInfo(name = "file_name")
    val fileName: String,

    /** 文件大小（字节） */
    @ColumnInfo(name = "file_size")
    val fileSize: Long,

    /** 文件修改时间戳（毫秒） */
    @ColumnInfo(name = "last_modified")
    val lastModified: Long,

    /** MIME 类型（如 image/jpeg） */
    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    /** 图片宽度（像素），0 = 待扫描 */
    val width: Int = 0,

    /** 图片高度（像素），0 = 待扫描 */
    val height: Int = 0,

    /** 所属相册 ID */
    @ColumnInfo(name = "album_id")
    val albumId: Long,

    /** 所属仓库 ID */
    @ColumnInfo(name = "repository_id")
    val repositoryId: Long
)
