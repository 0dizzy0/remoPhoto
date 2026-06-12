package com.remophoto.data.repository

import com.remophoto.data.local.dao.ImageDao
import com.remophoto.data.local.entity.ImageEntity

/**
 * 图片数据仓库
 *
 * 封装 ImageDao，协调本地数据源的访问。
 */
class ImageRepository(private val imageDao: ImageDao) {

    fun getImagesByAlbum(albumId: Long) = imageDao.getImagesByAlbum(albumId)

    suspend fun getImagesByAlbumPaged(albumId: Long, limit: Int, offset: Int) =
        imageDao.getImagesByAlbumPaged(albumId, limit, offset)

    suspend fun getFirstImageByAlbum(albumId: Long) =
        imageDao.getFirstImageByAlbum(albumId)

    suspend fun getImageById(imageId: Long) =
        imageDao.getImageById(imageId)

    suspend fun getImageByPath(path: String) =
        imageDao.getImageByPath(path)

    suspend fun getImageCountByAlbum(albumId: Long) =
        imageDao.getImageCountByAlbum(albumId)

    suspend fun upsertAll(images: List<ImageEntity>) =
        imageDao.upsertAll(images)

    suspend fun insert(image: ImageEntity) =
        imageDao.insert(image)

    suspend fun delete(image: ImageEntity) =
        imageDao.delete(image)

    suspend fun deleteByRepository(repoId: Long) =
        imageDao.deleteByRepository(repoId)

    suspend fun getTotalFileSize() =
        imageDao.getTotalFileSize()

    suspend fun getTotalCount() =
        imageDao.getTotalCount()
}
