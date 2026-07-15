package com.remophoto.data.remote

import com.remophoto.data.local.entity.RemoteType
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** 生成不含凭据、可稳定去重的远程连接身份键。 */
object RemoteConnectionIdentity {
    fun create(
        type: RemoteType,
        host: String,
        port: Int,
        shareName: String? = null,
        rootPath: String? = null,
        domain: String? = null,
        username: String? = null,
    ): String {
        require(port in 1..65535) { "远程端口超出有效范围" }
        val fields = when (type) {
            RemoteType.HTTP_MDNS -> listOf(
                type.name,
                normalizeHost(host),
                port.toString(),
            )

            RemoteType.SMB -> listOf(
                type.name,
                normalizeHost(host),
                port.toString(),
                normalizeName(requireNotNull(shareName) { "SMB 共享名不能为空" }),
                normalizePath(rootPath),
                normalizeAccount(domain),
                normalizeAccount(requireNotNull(username) { "SMB 用户名不能为空" }),
            )
        }
        require(fields[1].isNotEmpty()) { "远程主机不能为空" }
        if (type == RemoteType.SMB) {
            require(fields[3].isNotEmpty()) { "SMB 共享名不能为空" }
            require(fields[6].isNotEmpty()) { "SMB 用户名不能为空" }
        }

        // 长度前缀避免不同字段组合产生分隔符碰撞。
        val canonical = fields.joinToString(separator = "") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    internal fun normalizeHost(value: String): String = normalizeUnicode(value)
        .trim()
        .removePrefix("[")
        .removeSuffix("]")
        .trimEnd('.')
        .lowercase(Locale.ROOT)

    internal fun normalizePath(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val segments = normalizeUnicode(value)
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
        require(segments.none { it == ".." }) { "SMB 根目录不能包含上级路径" }
        return segments.joinToString("/")
    }

    private fun normalizeName(value: String): String = normalizeUnicode(value)
        .trim()
        .lowercase(Locale.ROOT)

    private fun normalizeAccount(value: String?): String = normalizeUnicode(value.orEmpty())
        .trim()
        .lowercase(Locale.ROOT)

    private fun normalizeUnicode(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC)
}
