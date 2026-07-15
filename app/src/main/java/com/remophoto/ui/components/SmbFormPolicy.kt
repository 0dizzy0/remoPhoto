package com.remophoto.ui.components

import com.remophoto.data.remote.RemoteErrorCategory
import com.remophoto.data.remote.smb.SmbPathCodec

data class SmbFormValues(
    val displayName: String,
    val host: String,
    val port: String,
    val shareName: String,
    val rootPath: String,
    val username: String,
    val domain: String,
    val password: String,
)

data class SmbFormErrors(
    val displayName: String? = null,
    val host: String? = null,
    val port: String? = null,
    val shareName: String? = null,
    val rootPath: String? = null,
    val username: String? = null,
    val password: String? = null,
) {
    val isEmpty: Boolean
        get() = listOf(displayName, host, port, shareName, rootPath, username, password).all { it == null }
}

object SmbFormPolicy {
    fun validate(values: SmbFormValues): SmbFormErrors {
        val port = values.port.trim().toIntOrNull()
        return SmbFormErrors(
            displayName = if (values.displayName.isBlank()) "请输入显示名称" else null,
            host = if (values.host.isBlank()) "请输入主机名或 IP 地址" else null,
            port = if (port == null || port !in 1..65535) "端口必须在 1 到 65535 之间" else null,
            shareName = if (values.shareName.isBlank()) "请输入共享名" else null,
            rootPath = runCatching { SmbPathCodec.normalizeRelative(values.rootPath) }
                .exceptionOrNull()
                ?.let { "相册根目录不能包含上级路径或无效字符" },
            username = if (values.username.isBlank()) "请输入用户名" else null,
            password = if (values.password.isEmpty()) "请输入密码" else null,
        )
    }

    fun actionableMessage(category: RemoteErrorCategory): String = when (category) {
        RemoteErrorCategory.AUTH_FAILED -> "认证失败，请检查用户名、域和密码"
        RemoteErrorCategory.HOST_UNREACHABLE -> "无法连接主机，请检查地址、端口和局域网连接"
        RemoteErrorCategory.SHARE_NOT_FOUND -> "未找到共享，请检查共享名"
        RemoteErrorCategory.ACCESS_DENIED -> "没有访问权限，请检查账号和共享权限"
        RemoteErrorCategory.TIMEOUT -> "连接超时，请确认服务器在线后重试"
        RemoteErrorCategory.UNSUPPORTED_DIALECT -> "服务器仅支持不安全的旧版 SMB 协议"
        RemoteErrorCategory.PATH_INVALID -> "子目录不存在或无法访问"
        RemoteErrorCategory.RESOURCE_LIMIT -> "服务器资源不足，请稍后重试"
        RemoteErrorCategory.CANCELLED -> "连接测试已取消"
        RemoteErrorCategory.UNSUPPORTED_PROTOCOL -> "当前服务器协议不受支持"
        RemoteErrorCategory.UNKNOWN -> "连接失败，请检查配置后重试"
    }
}
