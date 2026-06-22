package com.remophoto.ui.albumlist

import com.remophoto.domain.model.Album

internal data class AlbumLocation(val album: Album, val page: Int)

/** 纯函数定位器：模糊匹配名称并计算页码，不修改或筛选原列表。 */
internal object AlbumListLocator {
    fun locate(albums: List<Album>, query: String, perPage: Int): AlbumLocation? {
        if (perPage <= 0) return null
        val normalized = query.trim()
        if (normalized.isEmpty()) return null
        val index = albums.indexOfFirst { it.name.contains(normalized, ignoreCase = true) }
        if (index < 0) return null
        return AlbumLocation(albums[index], index / perPage + 1)
    }

    fun pageCount(itemCount: Int, perPage: Int): Int {
        if (perPage <= 0) return 1
        return ((itemCount.coerceAtLeast(0) + perPage - 1) / perPage).coerceAtLeast(1)
    }
}
