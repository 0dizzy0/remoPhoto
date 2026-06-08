package com.remophoto.di

import android.content.Context
import com.remophoto.RemoPhotoApp
import com.remophoto.data.local.AppDatabase
import com.remophoto.data.local.dao.AlbumDao
import com.remophoto.data.local.dao.CategoryDao
import com.remophoto.data.local.dao.ImageDao
import com.remophoto.data.local.dao.RepositoryDao
import com.remophoto.data.repository.AlbumRepository
import com.remophoto.data.repository.ImageRepository
import com.remophoto.data.repository.SettingsRepository
import com.remophoto.data.scanner.FileScanner
import com.remophoto.util.ImageLoaderFactory
import coil.ImageLoader

/**
 * 手动依赖注入容器
 *
 * 简单的手写 DI，避免引入 Hilt/Koin 增加 Phase 0 复杂度。
 * 所有依赖通过 Application 实例获取。
 *
 * 用法：
 * ```
 * val app = context.applicationContext as RemoPhotoApp
 * val repo = app.dependencyContainer.imageRepository
 * ```
 */
class DependencyContainer(private val app: RemoPhotoApp) {

    // ===== 数据库 & DAO =====

    val database: AppDatabase by lazy { app.database }

    val repositoryDao: RepositoryDao by lazy { database.repositoryDao() }
    val imageDao: ImageDao by lazy { database.imageDao() }
    val albumDao: AlbumDao by lazy { database.albumDao() }
    val categoryDao: CategoryDao by lazy { database.categoryDao() }

    // ===== Repository =====

    val imageRepository: ImageRepository by lazy { ImageRepository(imageDao) }
    val albumRepository: AlbumRepository by lazy { AlbumRepository(albumDao) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(app) }

    // ===== 工具类 =====

    val imageLoader: ImageLoader by lazy { ImageLoaderFactory.create(app) }
    val fileScanner: FileScanner by lazy { FileScanner(app) }

    // PermissionHelper 需要 Activity 实例，不在此处创建
    // 由各 Activity 自行创建和管理
}

/**
 * 便捷扩展属性：从 RemoPhotoApp 获取依赖容器
 */
val RemoPhotoApp.dependencies: DependencyContainer
    get() = dependencyContainer

/**
 * 便捷扩展属性：从 Context 获取依赖容器
 */
val Context.dependencies: DependencyContainer
    get() = (applicationContext as RemoPhotoApp).dependencyContainer

