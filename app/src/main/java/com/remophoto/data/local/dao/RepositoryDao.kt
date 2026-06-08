package com.remophoto.data.local.dao

import androidx.room.*
import com.remophoto.data.local.entity.RepositoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 图片仓库 DAO
 */
@Dao
interface RepositoryDao {

    /** 查询所有仓库（Flow 响应式） */
    @Query("SELECT * FROM image_repositories ORDER BY added_time DESC")
    fun getAllRepositories(): Flow<List<RepositoryEntity>>

    /** 查询所有仓库（挂起函数） */
    @Query("SELECT * FROM image_repositories ORDER BY added_time DESC")
    suspend fun getAllRepositoriesList(): List<RepositoryEntity>

    /** 根据 ID 查询仓库 */
    @Query("SELECT * FROM image_repositories WHERE id = :repoId LIMIT 1")
    suspend fun getRepositoryById(repoId: Long): RepositoryEntity?

    /** 更新扫描时间和图片数量 */
    @Query("UPDATE image_repositories SET last_scan_time = :scanTime, image_count = :count WHERE id = :repoId")
    suspend fun updateScanInfo(repoId: Long, scanTime: Long, count: Int)

    /** 更新图片数量 */
    @Query("UPDATE image_repositories SET image_count = :count WHERE id = :repoId")
    suspend fun updateImageCount(repoId: Long, count: Int)

    /** 插入仓库 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(repository: RepositoryEntity): Long

    /** 更新仓库 */
    @Update
    suspend fun update(repository: RepositoryEntity)

    /** 删除仓库 */
    @Delete
    suspend fun delete(repository: RepositoryEntity)

    /** 获取仓库总数 */
    @Query("SELECT COUNT(*) FROM image_repositories")
    suspend fun getRepositoryCount(): Int
}
