package com.remophoto.data.local.dao

import androidx.room.*
import com.remophoto.data.local.entity.ImageEntity
import kotlinx.coroutines.flow.Flow

data class AlbumCoverMetadata(
    val albumId: Long,
    val coverImageId: Long?
)

data class AlbumCoverPath(
    val albumId: Long,
    val coverPath: String?
)

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

    /** 根据 ID 查询单张图片 */
    @Query("SELECT * FROM images WHERE id = :imageId LIMIT 1")
    suspend fun getImageById(imageId: Long): ImageEntity?

    /** 批量查询每个相册按文件名升序的首图路径，用于动态封面。 */
    @Query(
        "SELECT i.album_id AS albumId, i.file_path AS coverPath FROM images i " +
            "WHERE i.album_id IN (:albumIds) AND i.id = (" +
            "SELECT x.id FROM images x WHERE x.album_id = i.album_id " +
            "ORDER BY x.file_name COLLATE NOCASE ASC, x.id ASC LIMIT 1)"
    )
    suspend fun getFirstImagePathsNameAsc(albumIds: List<Long>): List<AlbumCoverPath>

    /** 批量查询每个相册按文件名降序的首图路径，用于动态封面。 */
    @Query(
        "SELECT i.album_id AS albumId, i.file_path AS coverPath FROM images i " +
            "WHERE i.album_id IN (:albumIds) AND i.id = (" +
            "SELECT x.id FROM images x WHERE x.album_id = i.album_id " +
            "ORDER BY x.file_name COLLATE NOCASE DESC, x.id ASC LIMIT 1)"
    )
    suspend fun getFirstImagePathsNameDesc(albumIds: List<Long>): List<AlbumCoverPath>

    /** 批量查询每个相册按修改时间升序的首图路径，用于动态封面。 */
    @Query(
        "SELECT i.album_id AS albumId, i.file_path AS coverPath FROM images i " +
            "WHERE i.album_id IN (:albumIds) AND i.id = (" +
            "SELECT x.id FROM images x WHERE x.album_id = i.album_id " +
            "ORDER BY x.last_modified ASC, x.id ASC LIMIT 1)"
    )
    suspend fun getFirstImagePathsModifiedAsc(albumIds: List<Long>): List<AlbumCoverPath>

    /** 批量查询每个相册按修改时间降序的首图路径，用于动态封面。 */
    @Query(
        "SELECT i.album_id AS albumId, i.file_path AS coverPath FROM images i " +
            "WHERE i.album_id IN (:albumIds) AND i.id = (" +
            "SELECT x.id FROM images x WHERE x.album_id = i.album_id " +
            "ORDER BY x.last_modified DESC, x.id ASC LIMIT 1)"
    )
    suspend fun getFirstImagePathsModifiedDesc(albumIds: List<Long>): List<AlbumCoverPath>

    /** 批量查询每个相册按文件大小升序的首图路径，用于动态封面。 */
    @Query(
        "SELECT i.album_id AS albumId, i.file_path AS coverPath FROM images i " +
            "WHERE i.album_id IN (:albumIds) AND i.id = (" +
            "SELECT x.id FROM images x WHERE x.album_id = i.album_id " +
            "ORDER BY x.file_size ASC, x.id ASC LIMIT 1)"
    )
    suspend fun getFirstImagePathsSizeAsc(albumIds: List<Long>): List<AlbumCoverPath>

    /** 批量查询每个相册按文件大小降序的首图路径，用于动态封面。 */
    @Query(
        "SELECT i.album_id AS albumId, i.file_path AS coverPath FROM images i " +
            "WHERE i.album_id IN (:albumIds) AND i.id = (" +
            "SELECT x.id FROM images x WHERE x.album_id = i.album_id " +
            "ORDER BY x.file_size DESC, x.id ASC LIMIT 1)"
    )
    suspend fun getFirstImagePathsSizeDesc(albumIds: List<Long>): List<AlbumCoverPath>

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

    /** 自定义封面优先，否则返回按现有文件名规则选出的第一张图片。 */
    @Query(
        "SELECT id FROM images WHERE album_id = :albumId " +
            "ORDER BY CASE WHEN file_path = :coverPath THEN 0 ELSE 1 END, " +
            "file_name COLLATE NOCASE ASC LIMIT 1"
    )
    suspend fun getCoverImageId(albumId: Long, coverPath: String?): Long?

    /** 批量查询本地仓库相册封面，避免 HTTP /api/albums 的 N+1 Room 调用。 */
    @Query(
        "SELECT a.id AS albumId, " +
            "COALESCE(" +
            "(SELECT custom.id FROM images custom WHERE custom.album_id = a.id " +
            "AND custom.file_path = a.cover_image_path LIMIT 1), " +
            "(SELECT first_image.id FROM images first_image WHERE first_image.album_id = a.id " +
            "ORDER BY first_image.file_name COLLATE NOCASE ASC LIMIT 1)" +
            ") AS coverImageId " +
            "FROM albums a WHERE a.repository_id IN (:repositoryIds)"
    )
    suspend fun getAlbumCoverMetadata(repositoryIds: List<Long>): List<AlbumCoverMetadata>

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
