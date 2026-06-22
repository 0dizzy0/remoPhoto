package com.remophoto.ui.albumlist

import com.remophoto.domain.model.Album
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumListLocatorTest {
    private val albums = (1L..25L).map { id ->
        Album(id = id, name = "Travel-$id", directoryPath = "/$id", repositoryId = 1)
    }

    @Test
    fun `fuzzy search returns containing album and its page`() {
        val result = AlbumListLocator.locate(albums, "VEL-21", perPage = 10)

        assertEquals(21L, result?.album?.id)
        assertEquals(3, result?.page)
        assertEquals(25, albums.size) // 定位不能改变原列表
    }

    @Test
    fun `blank or missing query has no result`() {
        assertNull(AlbumListLocator.locate(albums, "  ", perPage = 10))
        assertNull(AlbumListLocator.locate(albums, "不存在", perPage = 10))
    }

    @Test
    fun `large remote repository computes all pages`() {
        assertEquals(262, AlbumListLocator.pageCount(itemCount = 5225, perPage = 20))
        assertEquals(1, AlbumListLocator.pageCount(itemCount = 0, perPage = 20))
    }
}
