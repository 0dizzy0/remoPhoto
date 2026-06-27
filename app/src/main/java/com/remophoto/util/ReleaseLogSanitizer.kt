package com.remophoto.util

/**
 * Release 文件日志的最小脱敏层。
 *
 * Debug 构建保留原始诊断信息；Release 构建在 [AppLogger] 中统一调用本工具，
 * 避免完整 URI、文件路径、局域网地址和用户自定义名称落盘。
 */
internal object ReleaseLogSanitizer {

    private val namedValue = Regex(
        """\b(deviceName|displayName|albumName|repositoryName|repoName|name|path|filePath|coverPath|deletedPath|uri|host)\s*=\s*(?:"[^"]*"|'[^']*'|[^,\s，)\]]+)""",
        RegexOption.IGNORE_CASE
    )
    private val uri = Regex(
        """\b(?:content|file|https?)://[^\s,，)\]"']+""",
        RegexOption.IGNORE_CASE
    )
    private val windowsPath = Regex(
        """(?<![\w])(?:[a-zA-Z]:[\\/])[^\s,，)\]"']+"""
    )
    private val androidPath = Regex(
        """(?<![\w])/(?:storage|sdcard|mnt|data)/[^\s,，)\]"']+""",
        RegexOption.IGNORE_CASE
    )
    private val ipv4 = Regex(
        """(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])"""
    )
    private val ipv6 = Regex(
        """(?<![a-fA-F0-9:])(?:[a-fA-F0-9]{0,4}:){2,}[a-fA-F0-9]{0,4}(?![a-fA-F0-9:])"""
    )
    private val quotedUserName = Regex("""(相册|仓库|设备)\s*["“][^"”]+["”]""")

    fun sanitize(value: String): String {
        var result = namedValue.replace(value) { match ->
            "${match.groupValues[1]}=<redacted>"
        }
        result = uri.replace(result, "<uri>")
        result = windowsPath.replace(result, "<path>")
        result = androidPath.replace(result, "<path>")
        result = ipv4.replace(result, "<ip>")
        result = ipv6.replace(result, "<ip>")
        return quotedUserName.replace(result) { match ->
            "${match.groupValues[1]} \"<redacted>\""
        }
    }
}
