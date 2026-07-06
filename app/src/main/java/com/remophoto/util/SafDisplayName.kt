package com.remophoto.util

import java.net.URLDecoder

/**
 * 从 SAF URI 的 document ID 中提取适合展示的末级名称。
 *
 * Android 10 的 DownloadsProvider 可能返回
 * `raw:/storage/emulated/0/Download/Folder` 形式的 document ID；
 * 直接解码 URI 最后一段会把完整内部路径暴露给 UI。
 */
internal object SafDisplayName {

    fun fromUriString(uriString: String): String? {
        val encodedSegment = uriString.trimEnd('/').substringAfterLast('/', "")
        if (encodedSegment.isBlank()) return null

        val decodedSegment = try {
            // SAF URI 使用百分号编码；未编码的 '+' 应保留为文件名字符，而不是表单空格。
            URLDecoder.decode(encodedSegment.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            encodedSegment
        }

        return decodedSegment
            .trimEnd('/')
            .substringAfterLast('/')
            .substringAfterLast(':')
            .trim()
            .takeIf { it.isNotBlank() }
    }
}
