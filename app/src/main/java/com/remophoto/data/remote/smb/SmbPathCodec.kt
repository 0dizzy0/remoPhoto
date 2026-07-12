package com.remophoto.data.remote.smb

import com.remophoto.data.remote.RemoteDataException
import com.remophoto.data.remote.RemoteErrorCategory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64

object SmbPathCodec {
    private const val ALBUM_SCHEME = "smb-album://"
    private val HASH = Regex("[a-f0-9]{64}")
    private val ALBUM_STORAGE = Regex("^smb-album://([1-9][0-9]*)/([a-f0-9]{64})/([A-Za-z0-9_-]+)$")

    fun normalizeRelative(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val parts = raw.replace('\\', '/').split('/')
        val normalized = ArrayList<String>(parts.size)
        for (part in parts) {
            when {
                part.isBlank() || part == "." -> Unit
                part == ".." -> invalidPath()
                part.indexOf('\u0000') >= 0 -> invalidPath()
                else -> normalized += Normalizer.normalize(part, Normalizer.Form.NFC)
            }
        }
        return normalized.joinToString("/")
    }

    fun validateEntryName(name: String): String {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFC)
        if (
            normalized.isBlank() || normalized == "." || normalized == ".." ||
            '/' in normalized || '\\' in normalized || '\u0000' in normalized
        ) invalidPath()
        return normalized
    }

    fun joinRelative(parent: String, childName: String): String {
        val safeParent = normalizeRelative(parent)
        val safeChild = validateEntryName(childName)
        return if (safeParent.isEmpty()) safeChild else "$safeParent/$safeChild"
    }

    /** 仅此方法生成发送给 SMBJ 的共享内路径。 */
    fun serverPath(rootPath: String?, relativePath: String): String {
        val root = normalizeRelative(rootPath)
        val relative = normalizeRelative(relativePath)
        return listOf(root, relative)
            .filter(String::isNotEmpty)
            .joinToString("\\")
            .replace('/', '\\')
    }

    fun opaqueKey(relativePath: String): String = sha256(normalizeRelative(relativePath))

    fun versionToken(fileSize: Long, lastModified: Long): String =
        "${fileSize.coerceAtLeast(0L).toString(36)}-${lastModified.coerceAtLeast(0L).toString(36)}"

    fun albumStorageValue(connectionId: Long, relativeDirectory: String): String {
        require(connectionId > 0L) { "SMB connectionId 必须大于 0" }
        val normalized = normalizeRelative(relativeDirectory)
        val encoded = if (normalized.isEmpty()) {
            "_"
        } else {
            Base64.getUrlEncoder().withoutPadding().encodeToString(normalized.toByteArray(StandardCharsets.UTF_8))
        }
        return "$ALBUM_SCHEME$connectionId/${opaqueKey(normalized)}/$encoded"
    }

    fun parseAlbumStorageValue(storageValue: String): AlbumLocator? {
        val match = ALBUM_STORAGE.matchEntire(storageValue) ?: return null
        val connectionId = match.groupValues[1].toLongOrNull() ?: return null
        val expectedHash = match.groupValues[2]
        if (!HASH.matches(expectedHash)) return null
        val encoded = match.groupValues[3]
        val relative = try {
            if (encoded == "_") "" else String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val normalized = try {
            normalizeRelative(relative)
        } catch (_: RemoteDataException) {
            return null
        }
        if (opaqueKey(normalized) != expectedHash) return null
        return AlbumLocator(connectionId, normalized)
    }

    data class AlbumLocator(val connectionId: Long, val relativeDirectory: String)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun invalidPath(): Nothing = throw RemoteDataException(
        RemoteErrorCategory.PATH_INVALID,
        "SMB 路径不合法",
    )
}
