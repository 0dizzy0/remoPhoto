package com.remophoto.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 用户-仓库访问权限关联表（P3 远期）
 */
@Entity(
    tableName = "user_repository_access",
    primaryKeys = ["userId", "repositoryId"],
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RepositoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["repositoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("userId"),
        Index("repositoryId")
    ]
)
data class UserRepositoryAccess(
    val userId: Long,
    val repositoryId: Long
)
