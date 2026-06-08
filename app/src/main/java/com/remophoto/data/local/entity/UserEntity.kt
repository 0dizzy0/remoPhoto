package com.remophoto.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户实体（P3 远期 — 多用户与权限管理）
 */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 用户名（显示名称） */
    val name: String,

    /** 密码哈希（SHA-256 + Salt） */
    @ColumnInfo(name = "password_hash")
    val passwordHash: String,

    /** 密码 Salt */
    val salt: String,

    /** 角色：ADMIN / VIEWER */
    val role: UserRole = UserRole.VIEWER,

    /** 创建时间戳（毫秒） */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** 最后登录时间戳（毫秒） */
    @ColumnInfo(name = "last_login_at")
    val lastLoginAt: Long? = null
)

/**
 * 用户角色枚举
 */
enum class UserRole {
    /** 管理员：全部权限 */
    ADMIN,

    /** 普通用户：仅浏览授权仓库 */
    VIEWER
}
