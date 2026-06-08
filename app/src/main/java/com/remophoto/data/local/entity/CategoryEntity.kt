package com.remophoto.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 分类标签实体
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 分类名称 */
    val name: String,

    /** 标签颜色（ARGB 整型） */
    val color: Int
)
