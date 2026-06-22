package com.remophoto.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 数据库迁移定义
 *
 * 版本 1 → 2: 添加查询性能索引
 * 版本 2 → 3: 新增远程连接支持（remote_connections 表 + RepositoryEntity 扩展）
 */
object Migrations {

    /**
     * v1 → v2: 为高频查询列添加索引
     *
     * - images.file_path: 加速 getImageByPath/getImagesByPaths 查询
     * - images(album_id, last_modified): 复合索引覆盖 getImagesByAlbum 的 WHERE + ORDER BY
     * - albums.directory_path: 加速 getAlbumByPath 查询
     * - image_repositories.uri_string: 加速 getRepositoryByUriString 查询
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 创建 images.file_path 索引（非唯一，SAF URI 可能因授权路径不同而重复）
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_images_file_path " +
                    "ON images (file_path)"
            )
            // 创建 images(album_id, last_modified) 复合索引
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_images_album_id_last_modified " +
                    "ON images (album_id, last_modified)"
            )
            // 删除旧的单列 album_id 索引（已被复合索引替代）
            db.execSQL("DROP INDEX IF EXISTS index_images_album_id")
            // 创建 albums.directory_path 索引
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_albums_directory_path " +
                    "ON albums (directory_path)"
            )
            // 创建 image_repositories.uri_string 索引
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_image_repositories_uri_string " +
                    "ON image_repositories (uri_string)"
            )
        }
    }

    /**
     * v2 → v3: 新增远程连接支持
     *
     * 1. 创建 remote_connections 表（存储远程连接元信息）
     * 2. image_repositories 新增 remote_connection_id 列（FK → remote_connections）
     * 3. 为 remote_connection_id 创建索引
     *
     * 纯增量迁移，无破坏性操作。
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 创建 remote_connections 表
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS remote_connections (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    type TEXT NOT NULL,
                    host TEXT NOT NULL,
                    port INTEGER NOT NULL,
                    display_name TEXT NOT NULL,
                    share_name TEXT,
                    username TEXT,
                    added_time INTEGER NOT NULL,
                    last_connected_time INTEGER,
                    status TEXT NOT NULL DEFAULT 'DISCONNECTED'
                )
                """.trimIndent()
            )

            // 2. 创建 remote_connections 索引
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_connections_host " +
                    "ON remote_connections (host)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_connections_status " +
                    "ON remote_connections (status)"
            )

            // 3. image_repositories 新增 remote_connection_id 列（仅列，FK 由应用层维护）
            db.execSQL(
                "ALTER TABLE image_repositories ADD COLUMN remote_connection_id INTEGER"
            )

            // 4. 为 remote_connection_id 创建索引
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_image_repositories_remote_connection_id " +
                    "ON image_repositories (remote_connection_id)"
            )
        }
    }

    /** v3 → v4: 缓存相册最近修改时间，迁移时由已有图片索引回填。 */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE albums ADD COLUMN last_modified INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "UPDATE albums SET last_modified = COALESCE(" +
                    "(SELECT MAX(images.last_modified) FROM images WHERE images.album_id = albums.id), 0)"
            )
        }
    }
}
