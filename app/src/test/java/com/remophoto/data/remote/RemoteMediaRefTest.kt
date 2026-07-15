package com.remophoto.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMediaRefTest {
    @Test
    fun `SMB reference round trips without endpoint account or path`() {
        val ref = RemoteMediaRef.Smb(
            connectionId = 42L,
            opaqueMediaKey = "a".repeat(64),
            variant = RemoteMediaVariant.THUMBNAIL,
            versionToken = "2s-9ix",
        )

        assertEquals(ref, RemoteMediaRef.Smb.parse(ref.storageValue))
        assertFalse(ref.storageValue.contains("server"))
        assertFalse(ref.storageValue.contains("share"))
        assertFalse(ref.storageValue.contains("photos"))
        assertTrue(ref.storageValue.isRemoteMediaAddress())
        assertEquals("smb:42:${"a".repeat(64)}:THUMBNAIL:2s-9ix:cover", ref.storageValue.remoteMediaCacheKey(":cover"))
    }

    @Test
    fun `SMB reference parser rejects malformed values`() {
        assertNull(RemoteMediaRef.Smb.parse("smb-media://0/${"a".repeat(64)}/original/1-1"))
        assertNull(RemoteMediaRef.Smb.parse("smb-media://1/not-a-key/original/1-1"))
        assertNull(RemoteMediaRef.Smb.parse("smb-media://1/${"a".repeat(64)}/preview/1-1"))
    }
}
