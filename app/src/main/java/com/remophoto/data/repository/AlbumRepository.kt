package com.remophoto.data.repository

import com.remophoto.data.local.dao.AlbumDao
import com.remophoto.data.local.entity.AlbumEntity

/**
 * 相册数据仓库
 *
 * 封装 AlbumDao，协调本地数据源的访问。
 */
class AlbumRepository(private val albumDao: AlbumDao) {

    fun getAllAlbums() = albumDao.getAllAlbums()

    suspend fun getAllAlbumsList() = albumDao.getAllAlbumsList()

    fun getAlbumsByRepository(repoId: Long) = albumDao.getAlbumsByRepository(repoId)

    suspend fun getAlbumsByRepositoryList(repoId: Long) = albumDao.getAlbumsByRepositoryList(repoId)

    suspend fun getAlbumsByParent(parentId: Long) = albumDao.getAlbumsByParent(parentId)

    fun getRootAlbums() = albumDao.getRootAlbums()

    suspend fun getRootAlbumsByRepository(repoId: Long) = albumDao.getRootAlbumsByRepository(repoId)

    suspend fun getAlbumsPaged(limit: Int, offset: Int) = albumDao.getAlbumsPaged(limit, offset)

    suspend fun getAlbumCount() = albumDao.getAlbumCount()

    suspend fun getAlbumById(albumId: Long) = albumDao.getAlbumById(albumId)

    suspend fun getAlbumByPath(path: String) = albumDao.getAlbumByPath(path)

    suspend fun updateCoverImage(albumId: Long, coverPath: String?) =
        albumDao.updateCoverImage(albumId, coverPath)

    suspend fun updateSortOrder(albumId: Long, sortOrder: String?) =
        albumDao.updateSortOrder(albumId, sortOrder)

    suspend fun updateImageCount(albumId: Long, count: Int) =
        albumDao.updateImageCount(albumId, count)

    suspend fun upsertAll(albums: List<AlbumEntity>) = albumDao.upsertAll(albums)

    suspend fun insert(album: AlbumEntity) = albumDao.insert(album)

    suspend fun delete(album: AlbumEntity) = albumDao.delete(album)

    suspend fun deleteByRepository(repoId: Long) = albumDao.deleteByRepository(repoId)
}
