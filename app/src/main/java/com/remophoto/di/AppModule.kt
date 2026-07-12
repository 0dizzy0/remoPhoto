package com.remophoto.di

import android.content.Context
import com.remophoto.RemoPhotoApp
import com.remophoto.data.local.AppDatabase
import com.remophoto.data.local.dao.AlbumDao
import com.remophoto.data.local.dao.CategoryDao
import com.remophoto.data.local.dao.ImageDao
import com.remophoto.data.local.dao.RemoteConnectionDao
import com.remophoto.data.local.dao.RepositoryDao
import com.remophoto.data.security.KeyStoreManager
import com.remophoto.data.remote.RemoteHttpClient
import com.remophoto.data.remote.HttpRemoteCatalogSource
import com.remophoto.data.remote.RemoteSourceRouter
import com.remophoto.data.remote.smb.SmbSessionManager
import com.remophoto.data.remote.smb.SmbCatalogScanner
import com.remophoto.data.remote.smb.SmbMediaResolver
import com.remophoto.data.remote.smb.SmbRemoteCatalogSource
import com.remophoto.data.remote.smb.SmbRepositoryExternalCleaner
import com.remophoto.data.repository.AlbumRepository
import com.remophoto.data.repository.ImageRepository
import com.remophoto.data.repository.RemoteConnectionRepository
import com.remophoto.data.repository.RemoteRepositoryLifecycleService
import com.remophoto.data.repository.RoomRemoteMetadataStore
import com.remophoto.data.repository.RepositoryManager
import com.remophoto.data.repository.SettingsRepository
import com.remophoto.data.scanner.FileScanner
import com.remophoto.domain.usecase.AlbumCoverManager
import com.remophoto.domain.usecase.CategoryManager
import com.remophoto.domain.usecase.CreateAlbumsUseCase
import com.remophoto.domain.usecase.ScanImagesUseCase
import com.remophoto.domain.usecase.SortImagesUseCase
import com.remophoto.domain.usecase.SyncRemoteRepositoryUseCase
import com.remophoto.domain.usecase.SyncSmbRepositoryUseCase
import com.remophoto.util.ImageLoaderFactory
import com.remophoto.util.PermissionHelper
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
    val remoteConnectionDao: RemoteConnectionDao by lazy { database.remoteConnectionDao() }

    // ===== 安全 =====

    val keyStoreManager: KeyStoreManager by lazy {
        KeyStoreManager(app)
    }

    // ===== Repository =====

    val imageRepository: ImageRepository by lazy { ImageRepository(imageDao) }
    val albumRepository: AlbumRepository by lazy { AlbumRepository(albumDao) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(app) }

    // ===== 工具类 =====

    val imageLoader: ImageLoader by lazy { ImageLoaderFactory.create(app) }
    val thumbnailImageLoader: ImageLoader by lazy { ImageLoaderFactory.createThumbnailLoader(app) }
    val remoteThumbnailLoader: ImageLoader by lazy { ImageLoaderFactory.createRemoteThumbnailLoader(app) }
    val remoteImageLoader: ImageLoader by lazy { ImageLoaderFactory.createRemoteImageLoader(app) }
    val fileScanner: FileScanner by lazy { FileScanner(app) }

    // ===== UseCase =====

    val scanImagesUseCase: ScanImagesUseCase by lazy {
        ScanImagesUseCase(database, fileScanner, imageDao, albumDao, repositoryDao, albumCoverManager)
    }
    val createAlbumsUseCase: CreateAlbumsUseCase by lazy {
        CreateAlbumsUseCase(albumDao, imageDao)
    }
    val albumCoverManager: AlbumCoverManager by lazy {
        AlbumCoverManager(albumDao, imageDao)
    }
    val categoryManager: CategoryManager by lazy {
        CategoryManager(categoryDao)
    }
    val sortImagesUseCase: SortImagesUseCase by lazy { SortImagesUseCase() }

    // ===== Phase 4: 远程仓库 =====

    val remoteHttpClient: RemoteHttpClient by lazy { RemoteHttpClient() }

    private val remoteMetadataStore: RoomRemoteMetadataStore by lazy {
        RoomRemoteMetadataStore(
            database = database,
            connectionDao = remoteConnectionDao,
            repositoryDao = repositoryDao,
            imageDao = imageDao,
            albumDao = albumDao,
        )
    }

    val smbSessionManager: SmbSessionManager by lazy {
        SmbSessionManager(keyStoreManager)
    }

    val smbCatalogScanner: SmbCatalogScanner by lazy {
        SmbCatalogScanner(smbSessionManager)
    }

    val smbMediaResolver: SmbMediaResolver by lazy {
        SmbMediaResolver(imageDao, albumDao, remoteConnectionDao)
    }

    val syncSmbRepositoryUseCase: SyncSmbRepositoryUseCase by lazy {
        SyncSmbRepositoryUseCase(
            context = app,
            database = database,
            scanner = smbCatalogScanner,
            albumDao = albumDao,
            imageDao = imageDao,
            repositoryDao = repositoryDao,
            connectionDao = remoteConnectionDao,
        )
    }

    val remoteSourceRouter: RemoteSourceRouter by lazy {
        RemoteSourceRouter(
            listOf(
                HttpRemoteCatalogSource(remoteHttpClient),
                SmbRemoteCatalogSource(smbSessionManager),
            )
        )
    }

    val remoteConnectionRepository: RemoteConnectionRepository by lazy {
        RemoteConnectionRepository(remoteSourceRouter, remoteConnectionDao)
    }

    val remoteRepositoryLifecycleService: RemoteRepositoryLifecycleService by lazy {
        RemoteRepositoryLifecycleService(
            metadataStore = remoteMetadataStore,
            credentialStore = keyStoreManager,
            sessionInvalidator = smbSessionManager,
            externalCleaner = SmbRepositoryExternalCleaner(
                context = app,
                imageDao = imageDao,
                albumDao = albumDao,
                remoteThumbnailLoader = { remoteThumbnailLoader },
                remoteImageLoader = { remoteImageLoader },
            ),
        )
    }

    val syncRemoteRepositoryUseCase: SyncRemoteRepositoryUseCase by lazy {
        SyncRemoteRepositoryUseCase(
            database,
            albumDao,
            imageDao,
            repositoryDao,
            remoteConnectionRepository,
            syncSmbRepositoryUseCase,
        )
    }

    // PermissionHelper 需要 Activity 实例，不在此处创建
    // 由各 Activity 自行创建和管理

    /**
     * 工厂方法：创建 RepositoryManager
     *
     * RepositoryManager 依赖 PermissionHelper（需绑定 Activity），
     * 因此通过此工厂方法延迟创建。
     */
    fun createRepositoryManager(permissionHelper: PermissionHelper): RepositoryManager {
        return RepositoryManager.create(
            repositoryDao = repositoryDao,
            imageDao = imageDao,
            albumDao = albumDao,
            permissionHelper = permissionHelper
        )
    }
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
