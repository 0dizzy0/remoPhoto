package com.remophoto.data.local.dao

import androidx.room.*
import com.remophoto.data.local.entity.AlbumCategoryCrossRef
import com.remophoto.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 分类标签 DAO
 */
@Dao
interface CategoryDao {

    // ===== 分类 CRUD =====

    /** 查询所有分类 */
    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    /** 根据 ID 查询分类 */
    @Query("SELECT * FROM categories WHERE id = :categoryId LIMIT 1")
    suspend fun getCategoryById(categoryId: Long): CategoryEntity?

    /** 插入分类 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: CategoryEntity): Long

    /** 更新分类 */
    @Update
    suspend fun update(category: CategoryEntity)

    /** 删除分类 */
    @Delete
    suspend fun delete(category: CategoryEntity)

    // ===== 关联关系管理 =====

    /** 插入关联关系 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRef(crossRef: AlbumCategoryCrossRef)

    /** 删除特定相册-分类关联 */
    @Query("DELETE FROM album_category_cross_ref WHERE albumId = :albumId AND categoryId = :categoryId")
    suspend fun deleteCrossRef(albumId: Long, categoryId: Long)

    /** 批量删除某分类下指定相册的关联 */
    @Query("DELETE FROM album_category_cross_ref WHERE categoryId = :categoryId AND albumId IN (:albumIds)")
    suspend fun deleteCrossRefs(categoryId: Long, albumIds: List<Long>): Int

    /** 删除某相册的所有分类关联 */
    @Query("DELETE FROM album_category_cross_ref WHERE albumId = :albumId")
    suspend fun deleteCrossRefsByAlbum(albumId: Long)

    /** 删除某分类的所有相册关联 */
    @Query("DELETE FROM album_category_cross_ref WHERE categoryId = :categoryId")
    suspend fun deleteCrossRefsByCategory(categoryId: Long)

    /** 查询某分类下的所有相册 ID */
    @Query("SELECT albumId FROM album_category_cross_ref WHERE categoryId = :categoryId")
    suspend fun getAlbumIdsByCategory(categoryId: Long): List<Long>

    /** 查询某相册的所有分类 */
    @Query("SELECT c.* FROM categories c INNER JOIN album_category_cross_ref ac ON c.id = ac.categoryId WHERE ac.albumId = :albumId")
    suspend fun getCategoriesByAlbum(albumId: Long): List<CategoryEntity>
}
