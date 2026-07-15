package com.remophoto.data.remote

import com.remophoto.data.local.entity.RemoteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConnectionIdentityTest {
    @Test
    fun `normalizes equivalent HTTP endpoints`() {
        val first = RemoteConnectionIdentity.create(RemoteType.HTTP_MDNS, " Example.COM. ", 8080)
        val second = RemoteConnectionIdentity.create(RemoteType.HTTP_MDNS, "example.com", 8080)

        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `normalizes SMB separators accounts and unicode`() {
        val decomposed = "te\u0301st"
        val first = RemoteConnectionIdentity.create(
            type = RemoteType.SMB,
            host = "[FE80::1]",
            port = 445,
            shareName = "SHARE",
            rootPath = "\\照片\\.\\$decomposed\\",
            domain = " WORKGROUP ",
            username = " Alice ",
        )
        val second = RemoteConnectionIdentity.create(
            type = RemoteType.SMB,
            host = "fe80::1",
            port = 445,
            shareName = "share",
            rootPath = "照片/tést",
            domain = "workgroup",
            username = "alice",
        )

        assertEquals(first, second)
    }

    @Test
    fun `keeps different SMB roots and users distinct`() {
        val base = RemoteConnectionIdentity.create(
            RemoteType.SMB, "server", 445, "share", "photos", "", "alice"
        )
        val otherRoot = RemoteConnectionIdentity.create(
            RemoteType.SMB, "server", 445, "share", "archive", "", "alice"
        )
        val otherUser = RemoteConnectionIdentity.create(
            RemoteType.SMB, "server", 445, "share", "photos", "", "bob"
        )

        assertNotEquals(base, otherRoot)
        assertNotEquals(base, otherUser)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects parent traversal in SMB root`() {
        RemoteConnectionIdentity.create(
            RemoteType.SMB, "server", 445, "share", "photos/../secret", "", "alice"
        )
    }
}
