package com.remophoto.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.remophoto.data.local.dao.AlbumDao
import com.remophoto.data.local.dao.CategoryDao
import com.remophoto.data.local.dao.ImageDao
import com.remophoto.data.local.dao.RepositoryDao
import com.remophoto.data.local.entity.AlbumCategoryCrossRef
import com.remophoto.data.local.entity.AlbumEntity
import com.remophoto.data.local.entity.CategoryEntity
import com.remophoto.data.local.entity.ImageEntity
import com.remophoto.data.local.entity.RepositoryEntity
import com.remophoto.data.local.entity.UserEntity
import com.remophoto.data.local.entity.UserRepositoryAccess

/**
 * Room 数据库定义
 *
 * 包含所有 Entity 和 DAO。
 * 版本 1 — Phase 0 初始建表。
 */
@Database(
    entities = [
        RepositoryEntity::class,
        ImageEntity::class,
        AlbumEntity::class,
        CategoryEntity::class,
        AlbumCategoryCrossRef::class,
        // P3 远期表
        UserEntity::class,
        UserRepositoryAccess::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun repositoryDao(): RepositoryDao
    abstract fun imageDao(): ImageDao
    abstract fun albumDao(): AlbumDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        private const val DATABASE_NAME = "remophoto.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 获取数据库单例
         *
         * 使用双检锁确保线程安全。
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        /**
         * 关闭数据库实例（用于导入前释放文件锁）
         *
         * 导入完成后需要重启 App 进程重新初始化 Room。
         */
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                // 生产环境请移除 destructive migration，使用正确的 Migration
                // .fallbackToDestructiveMigration()
                .build()
        }
    }
}
