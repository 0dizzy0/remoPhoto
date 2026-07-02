package com.remophoto.ui.albumlist

import com.remophoto.domain.model.Album
import com.remophoto.domain.model.AlbumSortOrder
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

    @Test
    fun `page count covers boundaries and invalid input`() {
        assertEquals(1, AlbumListLocator.pageCount(itemCount = -1, perPage = 20))
        assertEquals(1, AlbumListLocator.pageCount(itemCount = 1, perPage = 20))
        assertEquals(1, AlbumListLocator.pageCount(itemCount = 20, perPage = 20))
        assertEquals(2, AlbumListLocator.pageCount(itemCount = 21, perPage = 20))
        assertEquals(3, AlbumListLocator.pageCount(itemCount = 41, perPage = 20))
        assertEquals(1, AlbumListLocator.pageCount(itemCount = 41, perPage = 0))
    }

    @Test
    fun `unicode query is trimmed and located without changing list`() {
        val unicodeAlbums = listOf(
            album(1, "  空白相册"),
            album(2, "旅行-東京"),
            album(3, "📷 Family")
        )

        val result = AlbumListLocator.locate(unicodeAlbums, " 東京 ", perPage = 1)

        assertEquals(2L, result?.album?.id)
        assertEquals(2, result?.page)
        assertEquals(listOf(1L, 2L, 3L), unicodeAlbums.map(Album::id))
    }

    @Test
    fun `sorting is deterministic for equal counts and recursively sorts children`() {
        val input = listOf(
            album(
                id = 3,
                name = "beta",
                count = 2,
                children = listOf(album(32, "zeta"), album(31, "Alpha"))
            ),
            album(id = 2, name = "alpha", count = 2),
            album(id = 1, name = "Alpha", count = 2)
        )

        val sorted = AlbumListSorter.sortTree(input, AlbumSortOrder.IMAGE_COUNT_ASC)

        assertEquals(listOf(1L, 2L, 3L), sorted.map(Album::id))
        assertEquals(listOf(31L, 32L), sorted.last().children.map(Album::id))
    }

    @Test
    fun `all sort orders use declared primary key and deterministic name fallback`() {
        val input = listOf(
            album(id = 1, name = "B", count = 1, modified = 10),
            album(id = 2, name = "a", count = 3, modified = 30),
            album(id = 3, name = "C", count = 2, modified = 20)
        )

        assertEquals(
            listOf(2L, 1L, 3L),
            AlbumListSorter.sortTree(input, AlbumSortOrder.NAME_ASC).map(Album::id)
        )
        assertEquals(
            listOf(3L, 1L, 2L),
            AlbumListSorter.sortTree(input, AlbumSortOrder.NAME_DESC).map(Album::id)
        )
        assertEquals(
            listOf(1L, 3L, 2L),
            AlbumListSorter.sortTree(input, AlbumSortOrder.MODIFIED_ASC).map(Album::id)
        )
        assertEquals(
            listOf(2L, 3L, 1L),
            AlbumListSorter.sortTree(input, AlbumSortOrder.MODIFIED_DESC).map(Album::id)
        )
        assertEquals(
            listOf(1L, 3L, 2L),
            AlbumListSorter.sortTree(input, AlbumSortOrder.IMAGE_COUNT_ASC).map(Album::id)
        )
        assertEquals(
            listOf(2L, 3L, 1L),
            AlbumListSorter.sortTree(input, AlbumSortOrder.IMAGE_COUNT_DESC).map(Album::id)
        )
    }

    private fun album(
        id: Long,
        name: String,
        count: Int = 0,
        modified: Long = 0,
        children: List<Album> = emptyList()
    ) = Album(
        id = id,
        name = name,
        directoryPath = "/$id",
        repositoryId = 1,
        imageCount = count,
        lastModified = modified,
        children = children
    )
}
