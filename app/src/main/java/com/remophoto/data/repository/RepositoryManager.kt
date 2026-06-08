package com.remophoto.data.repository

import android.net.Uri
import com.remophoto.data.local.dao.AlbumDao
import com.remophoto.data.local.dao.ImageDao
import com.remophoto.data.local.dao.RepositoryDao
import com.remophoto.data.local.entity.RepositoryEntity
import com.remophoto.util.PermissionHelper
import kotlinx.coroutines.flow.Flow

/**
 * 仓库管理核心逻辑
 *
 * 封装仓库的添加、删除、验证、扫描信息更新等操作。
 * 注意：PermissionHelper 需要 Activity 实例，通过 [create] 工厂方法注入。
 *
 * @param repositoryDao 仓库 DAO
 * @param imageDao 图片 DAO（用于级联删除）
 * @param albumDao 相册 DAO（用于级联删除）
 * @param permissionHelper SAF 权限工具实例
 */
class RepositoryManager(
    private val repositoryDao: RepositoryDao,
    private val imageDao: ImageDao,
    private val albumDao: AlbumDao,
    private val permissionHelper: PermissionHelper
) {

    /**
     * 添加新仓库
     *
     * @param uri SAF 目录选择器返回的 content:// URI
     * @param name 仓库显示名称（默认取目录名）
     * @return 新仓库的 ID
     */
    suspend fun addRepository(uri: Uri, name: String): Long {
        // 去重：检查是否已存在相同 URI 的仓库
        val existing = repositoryDao.getRepositoryByUriString(uri.toString())
        if (existing != null) {
            throw IllegalStateException("该仓库已存在（「${existing.name}」）")
        }

        // 持久化 SAF 权限
        permissionHelper.persistUriPermission(uri)

        // 尝试获取实际文件路径
        val path = permissionHelper.getRealPathFromUri(uri)

        // 获取可读的目录名称
        val displayName = if (!name.isNullOrBlank()) {
            name
        } else {
            val docName = permissionHelper.getFileName(uri)
            if (!docName.isNullOrBlank()) {
                docName
            } else {
                // 回退：从 URI 路径提取并 URL 解码
                val rawName = uri.toString().substringAfterLast('/')
                try {
                    java.net.URLDecoder.decode(rawName, "UTF-8")
                } catch (_: Exception) {
                    rawName
                }
            }
        }

        val entity = RepositoryEntity(
            uriString = uri.toString(),
            path = path,
            name = displayName.ifBlank { "未命名仓库" },
            addedTime = System.currentTimeMillis(),
            lastScanTime = 0,
            imageCount = 0
        )

        return repositoryDao.insert(entity)
    }

    /**
     * 删除仓库（级联删除关联的图片和相册）
     *
     * @param repoId 仓库 ID
     */
    suspend fun deleteRepository(repoId: Long) {
        val repo = repositoryDao.getRepositoryById(repoId) ?: return

        // 级联删除：先删图片，再删相册，最后删仓库
        imageDao.deleteByRepository(repoId)
        albumDao.deleteByRepository(repoId)
        repositoryDao.delete(repo)
    }

    /**
     * 验证仓库的 SAF 权限是否仍然有效
     *
     * 如果权限已失效（如用户卸载重装后），返回 false。
     */
    fun validateRepository(repo: RepositoryEntity): Boolean {
        return try {
            val uri = Uri.parse(repo.uriString)
            permissionHelper.isUriPermissionValid(uri)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 验证所有仓库权限，返回失效的仓库 ID 列表
     */
    suspend fun validateAllRepositories(): List<Long> {
        val all = repositoryDao.getAllRepositoriesList()
        return all.filter { !validateRepository(it) }.map { it.id }
    }

    /**
     * 获取所有仓库（Flow 响应式）
     */
    fun getAllRepositories(): Flow<List<RepositoryEntity>> =
        repositoryDao.getAllRepositories()

    /**
     * 获取所有仓库（挂起函数）
     */
    suspend fun getAllRepositoriesList(): List<RepositoryEntity> =
        repositoryDao.getAllRepositoriesList()

    /**
     * 更新扫描信息
     */
    suspend fun updateScanInfo(repoId: Long, scanTime: Long, count: Int) {
        repositoryDao.updateScanInfo(repoId, scanTime, count)
    }

    /**
     * 根据 ID 获取仓库
     */
    suspend fun getRepositoryById(repoId: Long): RepositoryEntity? =
        repositoryDao.getRepositoryById(repoId)

    companion object {
        /**
         * 工厂方法：从 DependencyContainer 创建 RepositoryManager
         *
         * @param app RemoPhotoApp 实例
         * @param permissionHelper PermissionHelper 实例（需绑定 Activity）
         */
        fun create(
            repositoryDao: RepositoryDao,
            imageDao: ImageDao,
            albumDao: AlbumDao,
            permissionHelper: PermissionHelper
        ): RepositoryManager {
            return RepositoryManager(repositoryDao, imageDao, albumDao, permissionHelper)
        }
    }
}
