package com.remophoto.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class RemoteCacheStatsTest {

    @Test
    fun `counts cache entries but excludes Coil journal metadata`() {
        val cacheDir = Files.createTempDirectory("remote-cache-stats").toFile()
        try {
            cacheDir.resolve("journal").writeText("index")
            cacheDir.resolve("journal.tmp").writeText("temporary index")
            cacheDir.resolve("entry.0").writeBytes(ByteArray(128))
            cacheDir.resolve("nested").mkdir()
            cacheDir.resolve("nested/entry.1").writeBytes(ByteArray(256))

            assertEquals(384L, remoteCachePayloadSize(cacheDir))
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
