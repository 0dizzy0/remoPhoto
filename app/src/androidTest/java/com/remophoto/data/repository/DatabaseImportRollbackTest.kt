package com.remophoto.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.remophoto.data.local.AppDatabase
import com.remophoto.data.local.entity.CategoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DatabaseImportRollbackTest {
    private val context: Context by lazy {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File =
                File(testRoot(base), "files").apply { mkdirs() }

            override fun getCacheDir(): File =
                File(testRoot(base), "cache").apply { mkdirs() }

            override fun getDatabasePath(name: String): File =
                File(testRoot(base), "databases/$name").also {
                    it.parentFile?.mkdirs()
                }

            override fun deleteDatabase(name: String): Boolean {
                val database = getDatabasePath(name)
                val files = listOf(
                    database,
                    File("${database.path}-wal"),
                    File("${database.path}-shm"),
                    File("${database.path}-journal")
                )
                return files.fold(true) { deleted, file ->
                    (!file.exists() || file.delete()) && deleted
                }
            }
        }
    }

    private val settingsFile: File
        get() = File(
            context.filesDir,
            "datastore/${BackupImportPolicy.DATASTORE_FILE}"
        )

    @Before
    fun prepare() {
        AppDatabase.closeInstance()
        testRoot().deleteRecursively()
        context.deleteDatabase(BackupImportPolicy.DATABASE_FILE)
        context.getDatabasePath(BackupImportPolicy.DATABASE_FILE).parentFile?.mkdirs()
        settingsFile.delete()
        transferRoot().deleteRecursively()
        importedDatabaseFile().delete()
        backupZipFile().delete()
        testRoot().deleteRecursively()
    }

    @After
    fun cleanUp() {
        AppDatabase.closeInstance()
        context.deleteDatabase(BackupImportPolicy.DATABASE_FILE)
        settingsFile.delete()
        transferRoot().deleteRecursively()
        importedDatabaseFile().delete()
        backupZipFile().delete()
    }

    @Test
    fun failureAfterSettingsRestoreRollsBackDatabaseAndSettings() = runBlocking {
        val database = AppDatabase.getInstance(context)
        val originalId = database.categoryDao().insert(
            CategoryEntity(name = ORIGINAL_CATEGORY, color = 0x112233)
        )
        settingsFile.parentFile?.mkdirs()
        settingsFile.writeText(ORIGINAL_SETTINGS, Charsets.UTF_8)

        val importedDatabase = importedDatabaseFile()
        val backupZip = backupZipFile()
        createImportedDatabase(database, importedDatabase, originalId)
        createBackupZip(importedDatabase, backupZip)
        val observedStages = mutableListOf<ImportStage>()

        val result = DatabaseExporter.importDatabase(
            context = context,
            sourceUri = Uri.fromFile(backupZip),
            stageObserver = ImportStageObserver { stage ->
                observedStages += stage
                if (stage == ImportStage.SETTINGS_RESTORED) {
                    throw IOException(INJECTED_FAILURE)
                }
            }
        )

        assertFalse(result)
        assertEquals(
            listOf(ImportStage.DATABASE_REPLACED, ImportStage.SETTINGS_RESTORED),
            observedStages
        )
        val restored = AppDatabase.getInstance(context)
        assertEquals(
            ORIGINAL_CATEGORY,
            restored.categoryDao().getCategoryById(originalId)?.name
        )
        assertEquals(ORIGINAL_SETTINGS, settingsFile.readText(Charsets.UTF_8))
        restored.openHelper.writableDatabase.query("PRAGMA integrity_check").use { cursor ->
            cursor.moveToFirst()
            assertEquals("ok", cursor.getString(0))
        }
        assertFalse(
            transferRoot().listFiles().orEmpty().any { it.name.startsWith("import-") }
        )
    }

    private fun createImportedDatabase(
        database: AppDatabase,
        target: File,
        categoryId: Long
    ) {
        target.delete()
        val escapedPath = target.absolutePath.replace("'", "''")
        database.openHelper.writableDatabase.execSQL("VACUUM INTO '$escapedPath'")
        SQLiteDatabase.openDatabase(
            target.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { imported ->
            imported.execSQL(
                "UPDATE categories SET name = ? WHERE id = ?",
                arrayOf(IMPORTED_CATEGORY, categoryId)
            )
        }
    }

    private fun createBackupZip(database: File, target: File) {
        target.delete()
        ZipOutputStream(FileOutputStream(target).buffered()).use { zip ->
            addFile(zip, database, BackupImportPolicy.DATABASE_FILE)
            zip.putNextEntry(
                ZipEntry("settings/${BackupImportPolicy.DATASTORE_FILE}")
            )
            zip.write(IMPORTED_SETTINGS.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupImportPolicy.MANIFEST_FILE))
            zip.write(
                BackupManifest(
                    formatVersion = DatabaseExporter.FORMAT_VERSION,
                    databaseVersion = AppDatabase.SCHEMA_VERSION,
                    createdAt = 1L,
                    includesRemoteConnections = false,
                    remoteConnectionCount = 0
                ).toJson().toString().toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun transferRoot() = File(context.cacheDir, "database-transfer")

    private fun importedDatabaseFile() = File(context.cacheDir, "rollback-import.db")

    private fun backupZipFile() = File(context.cacheDir, "rollback-import.zip")

    private fun testRoot() =
        testRoot(InstrumentationRegistry.getInstrumentation().targetContext)

    private companion object {
        const val ORIGINAL_CATEGORY = "rollback-original"
        const val IMPORTED_CATEGORY = "rollback-imported"
        const val ORIGINAL_SETTINGS = "original-settings"
        const val IMPORTED_SETTINGS = "imported-settings"
        const val INJECTED_FAILURE = "injected-after-settings-restore"

        fun testRoot(context: Context) =
            File(context.cacheDir, "database-import-rollback-context")
    }
}
