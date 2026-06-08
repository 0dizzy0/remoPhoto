package com.remophoto.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 根图片仓库实体
 */
@Entity(tableName = "image_repositories")
data class RepositoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 目录 URI 字符串（SAF 持久化授权后的 content:// URI） */
    @ColumnInfo(name = "uri_string")
    val uriString: String,

    /** 目录绝对路径（解析后缓存，可能为 null） */
    val path: String?,

    /** 仓库显示名称 */
    val name: String,

    /** 添加时间戳（毫秒） */
    @ColumnInfo(name = "added_time")
    val addedTime: Long,

    /** 最后扫描时间戳（毫秒），0 = 未扫描 */
    @ColumnInfo(name = "last_scan_time")
    val lastScanTime: Long = 0,

    /** 图片数量（冗余字段，加速展示） */
    @ColumnInfo(name = "image_count")
    val imageCount: Int = 0
)
