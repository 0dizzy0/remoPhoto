package com.remophoto.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseLogSanitizerTest {

    @Test
    fun `redacts network and user controlled values`() {
        val sanitized = ReleaseLogSanitizer.sanitize(
            "设备 name=\"Living Room\" @ 192.168.1.8:8080，连接 https://192.168.1.8/api\n" +
                "java.net.UnknownHostException: family-phone.local"
        )

        assertFalse(sanitized.contains("Living Room"))
        assertFalse(sanitized.contains("192.168.1.8"))
        assertFalse(sanitized.contains("https://"))
        assertFalse(sanitized.contains("family-phone.local"))
        assertTrue(sanitized.contains("name=<redacted>"))
        assertTrue(sanitized.contains("<ip>"))
        assertTrue(sanitized.contains("<uri>"))
    }

    @Test
    fun `redacts Android Windows and content paths`() {
        val sanitized = ReleaseLogSanitizer.sanitize(
            "path=/storage/emulated/0/DCIM/private.jpg, " +
                "backup=D:\\Fixtures\\Pictures\\private.jpg, " +
                "uri=content://com.android.providers.media/document/image%3A1"
        )

        assertFalse(sanitized.contains("private.jpg"))
        assertFalse(sanitized.contains("content://"))
        assertTrue(sanitized.contains("path=<redacted>"))
        assertTrue(sanitized.contains("<path>"))
        assertTrue(sanitized.contains("uri=<redacted>"))
    }

    @Test
    fun `redacts unquoted and quoted file names`() {
        val sanitized = ReleaseLogSanitizer.sanitize(
            "封面已设置: fileName=Family Trip 2025.jpg, images=3, " +
                "fallback filename='private.png'"
        )

        assertFalse(sanitized.contains("Family Trip 2025.jpg"))
        assertFalse(sanitized.contains("private.png"))
        assertTrue(sanitized.contains("fileName=<redacted>"))
        assertTrue(sanitized.contains("filename=<redacted>"))
        assertTrue(sanitized.contains("images=3"))
    }

    @Test
    fun `redacts album repository and device labels`() {
        val sanitized = ReleaseLogSanitizer.sanitize(
            "album=\"primary:Pictures/Family Album\", " +
                "repository=Private Library, device=Living Room, repoId=3"
        )

        assertFalse(sanitized.contains("Family Album"))
        assertFalse(sanitized.contains("Private Library"))
        assertFalse(sanitized.contains("Living Room"))
        assertTrue(sanitized.contains("album=<redacted>"))
        assertTrue(sanitized.contains("repository=<redacted>"))
        assertTrue(sanitized.contains("device=<redacted>"))
        assertTrue(sanitized.contains("repoId=3"))
    }

    @Test
    fun `redacts complete unquoted names containing spaces and punctuation`() {
        val sanitized = ReleaseLogSanitizer.sanitize(
            "点击相册卡片: id=7, " +
                "name=primary:Pictures/123-(C90) [私人相册 标题], children=0\n" +
                "returnRoute=album_list?repoId=4&repoName=客厅 图库(旧版)"
        )

        assertFalse(sanitized.contains("私人相册"))
        assertFalse(sanitized.contains("客厅 图库"))
        assertTrue(sanitized.contains("name=<redacted>"))
        assertTrue(sanitized.contains("repoName=<redacted>"))
        assertTrue(sanitized.contains("children=0"))
    }

    @Test
    fun `redacts category labels and keeps category counters`() {
        val sanitized = ReleaseLogSanitizer.sanitize(
            "categoryName=Family Trips, category=\"Private Collection\"(id=2), " +
                "分类“Weekend Photos”，匹配相册=1, elapsedMs=42"
        )

        assertFalse(sanitized.contains("Family Trips"))
        assertFalse(sanitized.contains("Private Collection"))
        assertFalse(sanitized.contains("Weekend Photos"))
        assertTrue(sanitized.contains("categoryName=<redacted>"))
        assertTrue(sanitized.contains("category=<redacted>"))
        assertTrue(sanitized.contains("分类 \"<redacted>\""))
        assertTrue(sanitized.contains("匹配相册=1"))
        assertTrue(sanitized.contains("elapsedMs=42"))
    }

    @Test
    fun `keeps operational counters for diagnostics`() {
        val sanitized = ReleaseLogSanitizer.sanitize(
            "扫描索引完成: images=267995, elapsedMs=4200, repoId=3"
        )

        assertTrue(sanitized.contains("images=267995"))
        assertTrue(sanitized.contains("elapsedMs=4200"))
        assertTrue(sanitized.contains("repoId=3"))
    }
}
