package com.remophoto.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 数据库迁移定义
 *
 * 版本 1 → 2: 添加查询性能索引
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
}
