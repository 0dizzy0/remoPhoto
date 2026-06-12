package com.remophoto.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 远程连接配置实体
 *
 * 存储 SMB / HTTP(mDNS) 远程仓库的连接元信息。
 * 凭据（SMB 密码、HTTP Token）不存于 DB，而是通过 Android Keystore 加密存储。
 *
 * 导出行为：此表数据随 Room DB 一同导出，但凭据保留在 Keystore 中不会被导出。
 * 导入后所有连接标记为 DISCONNECTED，需用户重新输入凭据。
 */
@Entity(
    tableName = "remote_connections",
    indices = [
        Index("host"),
        Index("status")
    ]
)
data class RemoteConnectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 远程协议类型 */
    val type: RemoteType,

    /** 远程主机 IP 地址 */
    val host: String,

    /** 远程端口（HTTP 默认 8080，SMB 默认 445） */
    val port: Int,

    /** 显示名称（mDNS 服务名 或 用户自定义名称） */
    @ColumnInfo(name = "display_name")
    val displayName: String,

    /** SMB 共享文件夹名称（仅 SMB 类型使用，HTTP 为 null） */
    @ColumnInfo(name = "share_name")
    val shareName: String? = null,

    /** 登录用户名（仅 SMB 类型使用，HTTP 为 null） */
    val username: String? = null,

    /** 添加时间戳（毫秒） */
    @ColumnInfo(name = "added_time")
    val addedTime: Long,

    /** 最后成功连接时间戳（毫秒），null = 尚未成功连接过 */
    @ColumnInfo(name = "last_connected_time")
    val lastConnectedTime: Long? = null,

    /** 当前连接状态 */
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED
)

/**
 * 远程仓库协议类型
 */
enum class RemoteType {
    /** Windows/macOS/Linux 电脑 SMB 共享文件夹 */
    SMB,

    /** remoPhoto 设备间 HTTP Server + mDNS 互访 */
    HTTP_MDNS
}

/**
 * 远程连接状态
 */
enum class ConnectionStatus {
    /** 已连接 — 上次 ping 成功 */
    CONNECTED,

    /** 未连接 — 从未连接或主动断开 */
    DISCONNECTED,

    /** 连接错误 — 上次 ping 失败（网络不可达、认证失败等） */
    ERROR
}
