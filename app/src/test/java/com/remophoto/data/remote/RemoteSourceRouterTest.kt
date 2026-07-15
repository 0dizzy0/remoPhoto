package com.remophoto.data.remote

import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSourceRouterTest {
    @Test
    fun `routes by remote type`() {
        val http = StubSource(RemoteType.HTTP_MDNS)
        val router = RemoteSourceRouter(listOf(http))

        assertTrue(router.sourceFor(RemoteType.HTTP_MDNS) === http)
    }

    @Test
    fun `reports unsupported type without falling back to HTTP`() {
        val router = RemoteSourceRouter(listOf(StubSource(RemoteType.HTTP_MDNS)))

        val error = runCatching { router.sourceFor(RemoteType.SMB) }.exceptionOrNull()

        assertTrue(error is RemoteDataException)
        assertEquals(RemoteErrorCategory.UNSUPPORTED_PROTOCOL, (error as RemoteDataException).category)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects duplicate sources for one protocol`() {
        RemoteSourceRouter(
            listOf(
                StubSource(RemoteType.HTTP_MDNS),
                StubSource(RemoteType.HTTP_MDNS),
            )
        )
    }
}

private class StubSource(override val type: RemoteType) : RemoteCatalogSource {
    override suspend fun testConnection(connection: RemoteConnectionEntity): Boolean = true

    override suspend fun fetchAlbums(
        connection: RemoteConnectionEntity,
    ): List<RemoteAlbumRecord> = emptyList()

    override suspend fun fetchMediaPage(
        connection: RemoteConnectionEntity,
        albumRemoteKey: String,
        page: Int,
        pageSize: Int,
    ): RemoteMediaPage = RemoteMediaPage(emptyList(), 0, page, pageSize)

    override fun mediaRef(
        connection: RemoteConnectionEntity,
        mediaRemoteKey: String,
        variant: RemoteMediaVariant,
    ): RemoteMediaRef = RemoteMediaRef.HttpUrl(mediaRemoteKey)
}
