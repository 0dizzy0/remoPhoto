package com.remophoto.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 相册-分类关联表（多对多）
 */
@Entity(
    tableName = "album_category_cross_ref",
    primaryKeys = ["albumId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("albumId"),
        Index("categoryId")
    ]
)
data class AlbumCategoryCrossRef(
    val albumId: Long,
    val categoryId: Long
)
