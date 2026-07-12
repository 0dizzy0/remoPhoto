package com.remophoto.data.remote

import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRemoteCatalogSourceTest {
    private val connection = RemoteConnectionEntity(
        id = 7,
        type = RemoteType.HTTP_MDNS,
        host = "192.0.2.10",
        port = 8080,
        displayName = "fixture",
        addedTime = 1L,
    )

    @Test
    fun `maps existing HTTP DTOs into protocol neutral records`() = runTest {
        val api = FakeRemoteHttpApi()
        val source = HttpRemoteCatalogSource(api)

        val albums = source.fetchAlbums(connection)
        val page = source.fetchMediaPage(connection, "11", page = 2, pageSize = 20)

        assertEquals("11", albums.single().remoteKey)
        assertEquals("10", albums.single().parentRemoteKey)
        assertEquals("42", albums.single().coverMediaKey)
        assertEquals("42", page.items.single().remoteKey)
        assertEquals(2, page.page)
        assertEquals(11L, api.requestedAlbumId)
    }

    @Test
    fun `preserves existing HTTP storage values`() {
        val source = HttpRemoteCatalogSource(FakeRemoteHttpApi())

        val original = source.mediaRef(connection, "42", RemoteMediaVariant.ORIGINAL)
        val thumbnail = source.mediaRef(connection, "42", RemoteMediaVariant.THUMBNAIL)

        assertEquals("http://192.0.2.10:8080/api/image/42", original.storageValue)
        assertEquals("http://192.0.2.10:8080/api/image/42/thumb", thumbnail.storageValue)
    }

    @Test
    fun `rejects invalid HTTP opaque keys with stable category`() = runTest {
        val source = HttpRemoteCatalogSource(FakeRemoteHttpApi())

        val error = runCatching {
            source.fetchMediaPage(connection, "not-a-number", page = 1, pageSize = 50)
        }.exceptionOrNull()

        assertTrue(error is RemoteDataException)
        assertEquals(RemoteErrorCategory.PATH_INVALID, (error as RemoteDataException).category)
    }
}

private class FakeRemoteHttpApi : RemoteHttpApi {
    var requestedAlbumId: Long? = null

    override suspend fun ping(host: String, port: Int): Boolean = true

    override suspend fun getAlbums(host: String, port: Int): List<RemoteAlbumDto> = listOf(
        RemoteAlbumDto(
            id = 11,
            name = "相册",
            imageCount = 1,
            repositoryId = 3,
            parentAlbumId = 10,
            coverImageId = 42,
            lastModified = 123L,
        )
    )

    override suspend fun getImages(
        host: String,
        port: Int,
        albumId: Long,
        page: Int,
        pageSize: Int,
    ): RemoteImageListResponse {
        requestedAlbumId = albumId
        return RemoteImageListResponse(
            images = listOf(
                RemoteImageDto(
                    id = 42,
                    fileName = "图片.jpg",
                    fileSize = 100,
                    lastModified = 123L,
                    width = 10,
                    height = 20,
                    mimeType = "image/jpeg",
                    albumId = albumId,
                    repositoryId = 3,
                )
            ),
            totalCount = 21,
            page = page,
            pageSize = pageSize,
        )
    }
}
