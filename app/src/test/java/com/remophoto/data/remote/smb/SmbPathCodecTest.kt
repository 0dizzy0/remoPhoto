package com.remophoto.data.remote.smb

import com.remophoto.data.remote.RemoteDataException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbPathCodecTest {
    @Test
    fun `normalizes separators dot and unicode without changing case`() {
        assertEquals("相册/A", SmbPathCodec.normalizeRelative("相册\\.\\A"))
        assertNotEquals(SmbPathCodec.opaqueKey("Photos/A.jpg"), SmbPathCodec.opaqueKey("photos/A.jpg"))
        assertEquals("root\\相册\\A.jpg", SmbPathCodec.serverPath("root", "相册/A.jpg"))
    }

    @Test
    fun `rejects traversal and separator in entry name`() {
        assertTrue(runCatching { SmbPathCodec.normalizeRelative("root/../secret") }.exceptionOrNull() is RemoteDataException)
        assertTrue(runCatching { SmbPathCodec.validateEntryName("a/b") }.exceptionOrNull() is RemoteDataException)
    }

    @Test
    fun `album locator round trips and detects tampering`() {
        val storage = SmbPathCodec.albumStorageValue(9L, "中文 目录/子目录")
        val locator = SmbPathCodec.parseAlbumStorageValue(storage)

        assertEquals(9L, locator?.connectionId)
        assertEquals("中文 目录/子目录", locator?.relativeDirectory)
        val tampered = storage.dropLast(1) + if (storage.last() == 'A') "B" else "A"
        assertNull(SmbPathCodec.parseAlbumStorageValue(tampered))
    }
}
