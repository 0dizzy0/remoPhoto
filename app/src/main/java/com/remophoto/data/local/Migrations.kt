package com.remophoto.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.remote.RemoteConnectionIdentity

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

    /** v4 → v5: 补齐 SMB 元数据并以规范化 identity_key 唯一去重。 */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            data class ExistingConnection(
                val id: Long,
                val type: String,
                val host: String,
                val port: Int,
                val displayName: String,
                val shareName: String?,
                val username: String?,
                val addedTime: Long,
                val lastConnectedTime: Long?,
                val status: String,
                val identityKey: String,
            )

            val connections = mutableListOf<ExistingConnection>()
            val identities = mutableSetOf<String>()
            db.query(
                "SELECT id, type, host, port, display_name, share_name, username, " +
                    "added_time, last_connected_time, status FROM remote_connections ORDER BY id"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val typeName = cursor.getString(1)
                    val type = RemoteType.valueOf(typeName)
                    val host = cursor.getString(2)
                    val port = cursor.getInt(3)
                    val shareName = if (cursor.isNull(5)) null else cursor.getString(5)
                    val username = if (cursor.isNull(6)) null else cursor.getString(6)
                    val identity = RemoteConnectionIdentity.create(
                        type = type,
                        host = host,
                        port = port,
                        shareName = shareName,
                        username = username,
                    )
                    check(identities.add(identity)) {
                        "v4 远程连接规范化后存在重复项，已停止迁移以避免数据覆盖"
                    }
                    connections += ExistingConnection(
                        id = cursor.getLong(0),
                        type = typeName,
                        host = host,
                        port = port,
                        displayName = cursor.getString(4),
                        shareName = shareName,
                        username = username,
                        addedTime = cursor.getLong(7),
                        lastConnectedTime = if (cursor.isNull(8)) null else cursor.getLong(8),
                        status = cursor.getString(9),
                        identityKey = identity,
                    )
                }
            }

            db.execSQL(
                """
                CREATE TABLE remote_connections_v5 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    type TEXT NOT NULL,
                    host TEXT NOT NULL,
                    port INTEGER NOT NULL,
                    display_name TEXT NOT NULL,
                    share_name TEXT,
                    username TEXT,
                    domain TEXT,
                    root_path TEXT,
                    identity_key TEXT NOT NULL,
                    added_time INTEGER NOT NULL,
                    last_connected_time INTEGER,
                    status TEXT NOT NULL
                )
                """.trimIndent()
            )
            val insert = db.compileStatement(
                "INSERT INTO remote_connections_v5 " +
                    "(id, type, host, port, display_name, share_name, username, domain, root_path, " +
                    "identity_key, added_time, last_connected_time, status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?)"
            )
            connections.forEach { connection ->
                insert.clearBindings()
                insert.bindLong(1, connection.id)
                insert.bindString(2, connection.type)
                insert.bindString(3, connection.host)
                insert.bindLong(4, connection.port.toLong())
                insert.bindString(5, connection.displayName)
                connection.shareName?.let { insert.bindString(6, it) } ?: insert.bindNull(6)
                connection.username?.let { insert.bindString(7, it) } ?: insert.bindNull(7)
                insert.bindString(8, connection.identityKey)
                insert.bindLong(9, connection.addedTime)
                connection.lastConnectedTime?.let { insert.bindLong(10, it) } ?: insert.bindNull(10)
                insert.bindString(11, connection.status)
                insert.executeInsert()
            }
            db.execSQL("DROP TABLE remote_connections")
            db.execSQL("ALTER TABLE remote_connections_v5 RENAME TO remote_connections")
            db.execSQL("CREATE INDEX index_remote_connections_host ON remote_connections (host)")
            db.execSQL("CREATE INDEX index_remote_connections_status ON remote_connections (status)")
            db.execSQL(
                "CREATE UNIQUE INDEX index_remote_connections_identity_key " +
                    "ON remote_connections (identity_key)"
            )
        }
    }
}
