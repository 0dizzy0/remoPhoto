package com.remophoto.data.local.dao

import androidx.room.*
import com.remophoto.data.local.entity.ImageEntity
import kotlinx.coroutines.flow.Flow

/**
 * 图片索引 DAO
 */
@Dao
interface ImageDao {

    /** 按相册 ID 查询所有图片（Flow 响应式） */
    @Query("SELECT * FROM images WHERE album_id = :albumId ORDER BY last_modified ASC")
    fun getImagesByAlbum(albumId: Long): Flow<List<ImageEntity>>

    /** 按相册 ID 分页查询图片 */
    @Query("SELECT * FROM images WHERE album_id = :albumId ORDER BY last_modified ASC LIMIT :limit OFFSET :offset")
    suspend fun getImagesByAlbumPaged(albumId: Long, limit: Int, offset: Int): List<ImageEntity>

    /** 按相册 ID 和排序方式查询图片 */
    @Query("SELECT * FROM images WHERE album_id = :albumId ORDER BY last_modified ASC")
    suspend fun getImagesByAlbumSorted(albumId: Long): List<ImageEntity>

    /** 按仓库 ID 查询所有图片 */
    @Query("SELECT * FROM images WHERE repository_id = :repoId")
    suspend fun getImagesByRepository(repoId: Long): List<ImageEntity>

    /** 按仓库 ID 统计图片数量 */
    @Query("SELECT COUNT(*) FROM images WHERE repository_id = :repoId")
    suspend fun getImageCountByRepository(repoId: Long): Int

    /** 按相册 ID 统计图片数量 */
    @Query("SELECT COUNT(*) FROM images WHERE album_id = :albumId")
    suspend fun getImageCountByAlbum(albumId: Long): Int

    /** 查询相册中第一张图片（按文件名升序），用于自动封面 */
    @Query("SELECT * FROM images WHERE album_id = :albumId ORDER BY file_name COLLATE NOCASE ASC LIMIT 1")
    suspend fun getFirstImageByAlbum(albumId: Long): ImageEntity?

    /** 根据文件路径查询图片 */
    @Query("SELECT * FROM images WHERE file_path = :filePath LIMIT 1")
    suspend fun getImageByPath(filePath: String): ImageEntity?

    /** 根据文件路径列表查询图片 */
    @Query("SELECT * FROM images WHERE file_path IN (:paths)")
    suspend fun getImagesByPaths(paths: List<String>): List<ImageEntity>

    /** 批量插入（冲突时替换） */
    @Upsert
    suspend fun upsertAll(images: List<ImageEntity>)

    /** 插入单张图片 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: ImageEntity): Long

    /** 删除指定图片 */
    @Delete
    suspend fun delete(image: ImageEntity)

    /** 批量删除 */
    @Delete
    suspend fun deleteAll(images: List<ImageEntity>)

    /** 删除指定仓库下所有图片 */
    @Query("DELETE FROM images WHERE repository_id = :repoId")
    suspend fun deleteByRepository(repoId: Long)

    /** 删除指定相册下所有图片 */
    @Query("DELETE FROM images WHERE album_id = :albumId")
    suspend fun deleteByAlbum(albumId: Long)

    /** 获取表总行数 */
    @Query("SELECT COUNT(*) FROM images")
    suspend fun getTotalCount(): Int

    /** 获取所有图片的总文件大小 */
    @Query("SELECT COALESCE(SUM(file_size), 0) FROM images")
    suspend fun getTotalFileSize(): Long
}
