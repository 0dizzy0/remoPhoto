package com.remophoto.domain.usecase

import com.remophoto.data.local.dao.CategoryDao
import com.remophoto.data.local.entity.AlbumCategoryCrossRef
import com.remophoto.data.local.entity.CategoryEntity
import com.remophoto.util.AppLogger

/**
 * 分类标签管理器
 *
 * 封装分类标签的 CRUD 及相册-分类关联管理。
 */
class CategoryManager(
    private val categoryDao: CategoryDao
) {

    /** 获取所有分类 */
    fun getAllCategories() = categoryDao.getAllCategories()

    /** 根据 ID 获取分类 */
    suspend fun getCategoryById(categoryId: Long): CategoryEntity? =
        categoryDao.getCategoryById(categoryId)

    /** 创建分类 */
    suspend fun createCategory(name: String, color: Int): Long {
        AppLogger.i(TAG, "创建分类: name=\"$name\", color=$color")
        val entity = CategoryEntity(name = name.trim(), color = color)
        return categoryDao.insert(entity)
    }

    /** 更新分类 */
    suspend fun updateCategory(category: CategoryEntity) {
        AppLogger.i(TAG, "更新分类: id=${category.id}, name=\"${category.name}\"")
        categoryDao.update(category)
    }

    /** 删除分类（同时清理关联关系） */
    suspend fun deleteCategory(categoryId: Long) {
        AppLogger.i(TAG, "删除分类: id=$categoryId, 正在清理关联…")
        categoryDao.deleteCrossRefsByCategory(categoryId)
        val category = categoryDao.getCategoryById(categoryId)
        if (category != null) {
            categoryDao.delete(category)
        }
        AppLogger.i(TAG, "分类已删除: id=$categoryId")
    }

    /** 将相册关联到分类 */
    suspend fun assignAlbumToCategory(albumId: Long, categoryId: Long) {
        AppLogger.d(TAG, "关联相册到分类: albumId=$albumId, categoryId=$categoryId")
        categoryDao.insertCrossRef(
            AlbumCategoryCrossRef(albumId = albumId, categoryId = categoryId)
        )
    }

    /** 移除相册的某分类关联 */
    suspend fun removeAlbumFromCategory(albumId: Long, categoryId: Long) {
        AppLogger.d(TAG, "移除相册分类关联: albumId=$albumId, categoryId=$categoryId")
        categoryDao.deleteCrossRef(albumId, categoryId)
    }

    /** 获取相册的所有分类 */
    suspend fun getCategoriesForAlbum(albumId: Long): List<CategoryEntity> =
        categoryDao.getCategoriesByAlbum(albumId)

    /** 获取分类下的所有相册 ID */
    suspend fun getAlbumIdsForCategory(categoryId: Long): List<Long> =
        categoryDao.getAlbumIdsByCategory(categoryId)

    /** 批量设置相册的分类（先清除再设置） */
    suspend fun setAlbumCategories(albumId: Long, categoryIds: List<Long>) {
        AppLogger.i(TAG, "批量设置相册分类: albumId=$albumId, categoryIds=$categoryIds")
        categoryDao.deleteCrossRefsByAlbum(albumId)
        categoryIds.forEach { categoryId ->
            categoryDao.insertCrossRef(
                AlbumCategoryCrossRef(albumId = albumId, categoryId = categoryId)
            )
        }
    }

    companion object {
        private const val TAG = "CategoryMgr"
    }
}
