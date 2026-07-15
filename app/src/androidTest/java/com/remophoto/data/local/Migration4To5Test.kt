package com.remophoto.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.remote.RemoteConnectionIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration4To5Test {
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
    fun migrationBackfillsIdentityAndMatchesRoomSchema() {
        createCurrentDatabase()
        replaceRemoteConnectionsWithVersion4Table()
        insertVersion4HttpConnection()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .addMigrations(Migrations.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        try {
            val database = migrated.openHelper.writableDatabase
            assertEquals(5, database.version)
            database.query(
                "SELECT domain, root_path, identity_key FROM remote_connections WHERE id = 7"
            ).use { cursor ->
                cursor.moveToFirst()
                assertNull(if (cursor.isNull(0)) null else cursor.getString(0))
                assertNull(if (cursor.isNull(1)) null else cursor.getString(1))
                assertEquals(
                    RemoteConnectionIdentity.create(RemoteType.HTTP_MDNS, "Example.COM.", 8080),
                    cursor.getString(2),
                )
            }
        } finally {
            migrated.close()
        }
    }

    private fun createCurrentDatabase() {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.writableDatabase
        } finally {
            database.close()
        }
    }

    private fun replaceRemoteConnectionsWithVersion4Table() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DATABASE).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.execSQL("DROP INDEX index_remote_connections_identity_key")
            database.execSQL("DROP TABLE remote_connections")
            database.execSQL(
                """
                CREATE TABLE remote_connections (
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
            database.execSQL("CREATE INDEX index_remote_connections_host ON remote_connections (host)")
            database.execSQL("CREATE INDEX index_remote_connections_status ON remote_connections (status)")
            database.version = 4
        }
    }

    private fun insertVersion4HttpConnection() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DATABASE).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.execSQL(
                """
                INSERT INTO remote_connections (
                    id, type, host, port, display_name, share_name, username,
                    added_time, last_connected_time, status
                ) VALUES (7, 'HTTP_MDNS', 'Example.COM.', 8080, 'fixture', NULL, NULL, 1, NULL, 'DISCONNECTED')
                """.trimIndent()
            )
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-4-5-test.db"
    }
}
