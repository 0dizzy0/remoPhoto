package com.remophoto.ui.albumlist

import com.remophoto.domain.model.Album
import com.remophoto.domain.model.AlbumSortOrder
import java.util.Locale

/** 相册树纯排序规则：递归排序子节点，并为相同主键值提供确定性次级顺序。 */
internal object AlbumListSorter {
    fun sortTree(albums: List<Album>, order: AlbumSortOrder): List<Album> {
        val withSortedChildren = albums.map { album ->
            album.copy(children = sortTree(album.children, order))
        }
        return withSortedChildren.sortedWith(comparator(order))
    }

    private fun comparator(order: AlbumSortOrder): Comparator<Album> {
        val nameAsc = compareBy<Album>({ nameKey(it) }, { it.name }, { it.id })
        return when (order) {
            AlbumSortOrder.NAME_ASC -> nameAsc
            AlbumSortOrder.NAME_DESC ->
                compareByDescending<Album> { nameKey(it) }
                    .thenByDescending { it.name }
                    .thenBy { it.id }
            AlbumSortOrder.MODIFIED_ASC ->
                compareBy<Album> { it.lastModified }
                    .then(nameAsc)
            AlbumSortOrder.MODIFIED_DESC ->
                compareByDescending<Album> { it.lastModified }
                    .then(nameAsc)
            AlbumSortOrder.IMAGE_COUNT_ASC ->
                compareBy<Album> { it.imageCount }
                    .then(nameAsc)
            AlbumSortOrder.IMAGE_COUNT_DESC ->
                compareByDescending<Album> { it.imageCount }
                    .then(nameAsc)
        }
    }

    private fun nameKey(album: Album): String = album.name.lowercase(Locale.ROOT)
}
