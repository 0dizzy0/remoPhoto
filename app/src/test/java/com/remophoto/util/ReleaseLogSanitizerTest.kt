package com.remophoto.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseLogSanitizerTest {

    @Test
    fun `redacts network and user controlled values`() {
        val sanitized = ReleaseLogSanitizer.sanitize(
            "设备 name=\"Living Room\" @ 192.168.1.8:8080，连接 https://192.168.1.8/api"
        )

        assertFalse(sanitized.contains("Living Room"))
        assertFalse(sanitized.contains("192.168.1.8"))
        assertFalse(sanitized.contains("https://"))
        assertTrue(sanitized.contains("name=<redacted>"))
        assertTrue(sanitized.contains("<ip>"))
        assertTrue(sanitized.contains("<uri>"))
    }

    @Test
    fun `redacts Android Windows and content paths`() {
        val sanitized = ReleaseLogSanitizer.sanitize(
            "path=/storage/emulated/0/DCIM/private.jpg, " +
                "backup=C:\\Users\\name\\Pictures\\private.jpg, " +
                "uri=content://com.android.providers.media/document/image%3A1"
        )

        assertFalse(sanitized.contains("private.jpg"))
        assertFalse(sanitized.contains("content://"))
        assertTrue(sanitized.contains("path=<redacted>"))
        assertTrue(sanitized.contains("<path>"))
        assertTrue(sanitized.contains("uri=<redacted>"))
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
