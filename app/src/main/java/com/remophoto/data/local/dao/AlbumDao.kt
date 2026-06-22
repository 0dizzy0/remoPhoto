package com.remophoto.data.local.dao

import androidx.room.*
import com.remophoto.data.local.entity.AlbumEntity
import kotlinx.coroutines.flow.Flow

/**
 * 相册 DAO
 */
@Dao
interface AlbumDao {

    /** 查询所有相册（Flow 响应式） */
    @Query("SELECT * FROM albums ORDER BY name ASC")
    fun getAllAlbums(): Flow<List<AlbumEntity>>

    /** 查询所有相册（挂起函数） */
    @Query("SELECT * FROM albums ORDER BY name ASC")
    suspend fun getAllAlbumsList(): List<AlbumEntity>

    /** 按仓库 ID 查询相册 */
    @Query("SELECT * FROM albums WHERE repository_id = :repoId ORDER BY name ASC")
    fun getAlbumsByRepository(repoId: Long): Flow<List<AlbumEntity>>

    /** 按仓库 ID 查询全部相册（用于一次性构建完整层级和分页） */
    @Query("SELECT * FROM albums WHERE repository_id = :repoId ORDER BY name ASC")
    suspend fun getAlbumsByRepositoryList(repoId: Long): List<AlbumEntity>

    /** 按父相册 ID 查询子相册 */
    @Query("SELECT * FROM albums WHERE parent_album_id = :parentId ORDER BY name ASC")
    suspend fun getAlbumsByParent(parentId: Long): List<AlbumEntity>

    /** 查询根级相册（无父相册） */
    @Query("SELECT * FROM albums WHERE parent_album_id IS NULL ORDER BY name ASC")
    fun getRootAlbums(): Flow<List<AlbumEntity>>

    /** 按仓库 ID 查询根级相册 */
    @Query("SELECT * FROM albums WHERE repository_id = :repoId AND parent_album_id IS NULL ORDER BY name ASC")
    suspend fun getRootAlbumsByRepository(repoId: Long): List<AlbumEntity>

    /** 分页查询相册 */
    @Query("SELECT * FROM albums ORDER BY name ASC LIMIT :limit OFFSET :offset")
    suspend fun getAlbumsPaged(limit: Int, offset: Int): List<AlbumEntity>

    /** 查询相册总数 */
    @Query("SELECT COUNT(*) FROM albums")
    suspend fun getAlbumCount(): Int

    /** 根据 ID 查询相册 */
    @Query("SELECT * FROM albums WHERE id = :albumId LIMIT 1")
    suspend fun getAlbumById(albumId: Long): AlbumEntity?

    /** 根据目录路径查询相册 */
    @Query("SELECT * FROM albums WHERE directory_path = :path LIMIT 1")
    suspend fun getAlbumByPath(path: String): AlbumEntity?

    /** 更新封面图路径 */
    @Query("UPDATE albums SET cover_image_path = :coverPath WHERE id = :albumId")
    suspend fun updateCoverImage(albumId: Long, coverPath: String?)

    /** 更新排序方式 */
    @Query("UPDATE albums SET sort_order = :sortOrder WHERE id = :albumId")
    suspend fun updateSortOrder(albumId: Long, sortOrder: String?)

    /** 更新图片数量 */
    @Query("UPDATE albums SET image_count = :count WHERE id = :albumId")
    suspend fun updateImageCount(albumId: Long, count: Int)

    /** 更新父相册 ID（远程相册同步时将服务端 ID 映射为本地 ID） */
    @Query("UPDATE albums SET parent_album_id = :parentAlbumId WHERE id = :albumId")
    suspend fun updateParentAlbum(albumId: Long, parentAlbumId: Long?)

    /** 批量插入（冲突时替换） */
    @Upsert
    suspend fun upsertAll(albums: List<AlbumEntity>)

    /** 插入单个相册 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(album: AlbumEntity): Long

    /** 更新相册并保留主键以及相册-分类关联 */
    @Update
    suspend fun update(album: AlbumEntity)

    @Update
    suspend fun updateAll(albums: List<AlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(albums: List<AlbumEntity>): List<Long>

    /** 删除相册 */
    @Delete
    suspend fun delete(album: AlbumEntity)

    @Delete
    suspend fun deleteAll(albums: List<AlbumEntity>)

    /** 由图片索引一次性回填相册数量和最近修改时间，避免逐相册 N+1 查询。 */
    @Query(
        "UPDATE albums SET " +
            "image_count = (SELECT COUNT(*) FROM images WHERE images.album_id = albums.id), " +
            "last_modified = COALESCE((SELECT MAX(images.last_modified) FROM images WHERE images.album_id = albums.id), 0) " +
            "WHERE repository_id = :repoId"
    )
    suspend fun updateStatsFromImages(repoId: Long)

    /** 删除指定仓库下所有相册 */
    @Query("DELETE FROM albums WHERE repository_id = :repoId")
    suspend fun deleteByRepository(repoId: Long)
}
