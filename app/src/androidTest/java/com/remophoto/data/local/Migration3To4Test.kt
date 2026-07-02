package com.remophoto.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration3To4Test {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun prepare() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migrationBackfillsAlbumLastModifiedAndMatchesRoomSchema() {
        createVersion4Database()
        convertAlbumsTableToVersion3()
        insertVersion3Fixture()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .addMigrations(Migrations.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            val database = migrated.openHelper.writableDatabase
            assertEquals(4, database.version)
            database.query("SELECT last_modified FROM albums WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(20L, cursor.getLong(0))
            }
            database.query("SELECT last_modified FROM albums WHERE id = 2").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0L, cursor.getLong(0))
            }
        } finally {
            migrated.close()
        }
    }

    private fun createVersion4Database() {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.writableDatabase
        } finally {
            database.close()
        }
    }

    private fun convertAlbumsTableToVersion3() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DATABASE).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { database ->
            database.execSQL("PRAGMA foreign_keys = OFF")
            database.execSQL("DROP TABLE album_category_cross_ref")
            database.execSQL(
                """
                CREATE TABLE albums_v3 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    directory_path TEXT NOT NULL,
                    repository_id INTEGER NOT NULL,
                    parent_album_id INTEGER,
                    cover_image_path TEXT,
                    sort_order TEXT,
                    image_count INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO albums_v3 (
                    id, name, directory_path, repository_id, parent_album_id,
                    cover_image_path, sort_order, image_count
                )
                SELECT
                    id, name, directory_path, repository_id, parent_album_id,
                    cover_image_path, sort_order, image_count
                FROM albums
                """.trimIndent()
            )
            database.execSQL("DROP TABLE albums")
            database.execSQL("ALTER TABLE albums_v3 RENAME TO albums")
            database.execSQL("CREATE INDEX index_albums_parent_album_id ON albums (parent_album_id)")
            database.execSQL("CREATE INDEX index_albums_repository_id ON albums (repository_id)")
            database.execSQL("CREATE INDEX index_albums_directory_path ON albums (directory_path)")
            database.execSQL(
                """
                CREATE TABLE album_category_cross_ref (
                    albumId INTEGER NOT NULL,
                    categoryId INTEGER NOT NULL,
                    PRIMARY KEY(albumId, categoryId),
                    FOREIGN KEY(albumId) REFERENCES albums(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            database.execSQL(
                "CREATE INDEX index_album_category_cross_ref_albumId " +
                    "ON album_category_cross_ref (albumId)"
            )
            database.execSQL(
                "CREATE INDEX index_album_category_cross_ref_categoryId " +
                    "ON album_category_cross_ref (categoryId)"
            )
            database.version = 3
        }
    }

    private fun insertVersion3Fixture() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DATABASE).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { database ->
            database.execSQL(
                """
                INSERT INTO albums (
                    id, name, directory_path, repository_id, parent_album_id,
                    cover_image_path, sort_order, image_count
                ) VALUES (1, 'with-images', '/one', 1, NULL, NULL, NULL, 2)
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO albums (
                    id, name, directory_path, repository_id, parent_album_id,
                    cover_image_path, sort_order, image_count
                ) VALUES (2, 'empty', '/two', 1, NULL, NULL, NULL, 0)
                """.trimIndent()
            )
            insertImage(database, id = 1, albumId = 1, lastModified = 10)
            insertImage(database, id = 2, albumId = 1, lastModified = 20)
        }
    }

    private fun insertImage(
        database: SQLiteDatabase,
        id: Long,
        albumId: Long,
        lastModified: Long
    ) {
        database.execSQL(
            """
            INSERT INTO images (
                id, file_path, file_name, file_size, last_modified,
                mime_type, width, height, album_id, repository_id
            ) VALUES (?, ?, ?, 1, ?, 'image/jpeg', 1, 1, ?, 1)
            """.trimIndent(),
            arrayOf(id, "/$id.jpg", "$id.jpg", lastModified, albumId)
        )
    }

    private companion object {
        const val TEST_DATABASE = "migration-3-4-test.db"
    }
}
