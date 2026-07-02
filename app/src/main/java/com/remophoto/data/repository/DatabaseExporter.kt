package com.remophoto.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Process
import com.remophoto.data.local.AppDatabase
import com.remophoto.data.security.KeyStoreManager
import com.remophoto.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/** 一致性数据库备份及向下兼容导入。凭据位于 Keystore，永不进入备份。 */
object DatabaseExporter {
    private const val TAG = "DatabaseExporter"
    private const val DB_NAME = BackupImportPolicy.DATABASE_FILE
    private const val DATASTORE_FILE = BackupImportPolicy.DATASTORE_FILE
    private const val MANIFEST_FILE = BackupImportPolicy.MANIFEST_FILE
    internal const val FORMAT_VERSION = 1

    private val _events = MutableSharedFlow<ExportEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ExportEvent> = _events

    suspend fun exportDatabase(context: Context, destUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val workDir = createWorkDir(context, "export")
        val snapshot = File(workDir, DB_NAME)
        try {
            val database = AppDatabase.getInstance(context)
            val sqlite = database.openHelper.writableDatabase
            val remoteCount = queryCount(sqlite, "remote_connections")
            createSnapshot(sqlite, snapshot)

            val datastore = File(context.filesDir, "datastore/$DATASTORE_FILE")
            val manifest = BackupManifest(
                formatVersion = FORMAT_VERSION,
                databaseVersion = AppDatabase.SCHEMA_VERSION,
                createdAt = System.currentTimeMillis(),
                includesRemoteConnections = true,
                remoteConnectionCount = remoteCount
            )
            AppLogger.i(
                TAG,
                "导出开始: format=${manifest.formatVersion}, db=${manifest.databaseVersion}, " +
                    "remote=$remoteCount, settings=${datastore.exists()}"
            )

            context.contentResolver.openOutputStream(destUri, "wt")?.use { output ->
                ZipOutputStream(output.buffered()).use { zip ->
                    addFile(zip, snapshot, DB_NAME)
                    if (datastore.exists()) addFile(zip, datastore, "settings/$DATASTORE_FILE")
                    addText(zip, MANIFEST_FILE, manifest.toJson().toString())
                }
            } ?: return@withContext fail("无法打开导出目标")

            AppLogger.i(TAG, "导出成功: snapshot=${snapshot.length()} bytes")
            _events.tryEmit(ExportEvent.Success("导出成功"))
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "导出失败", e)
            fail("导出失败: ${e.message}")
        } finally {
            workDir.deleteRecursively()
        }
    }

    suspend fun importDatabase(context: Context, sourceUri: Uri): Boolean =
        importDatabase(context, sourceUri, ImportStageObserver.NONE)

    internal suspend fun importDatabase(
        context: Context,
        sourceUri: Uri,
        stageObserver: ImportStageObserver
    ): Boolean = withContext(Dispatchers.IO) {
        val workDir = createWorkDir(context, "import")
        val stagedDir = File(workDir, "staged").apply { mkdirs() }
        val sourceFile = File(workDir, "source")
        val currentBackup = File(workDir, "current.db")
        val settingsBackup = File(workDir, DATASTORE_FILE)
        var databaseClosed = false
        var hadOriginalSettings = false
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(sourceFile).use(input::copyTo)
            } ?: return@withContext fail("无法打开导入源")

            val sourceType = if (BackupImportPolicy.isZip(sourceFile)) "zip" else "raw_db"
            if (sourceType == "zip") {
                BackupImportPolicy.extractKnownEntries(sourceFile, stagedDir).forEach { entry ->
                    AppLogger.d(TAG, "暂存导入项: ${entry.name} (${entry.size} bytes)")
                }
            }
            else sourceFile.copyTo(File(stagedDir, DB_NAME), overwrite = true)

            val importedDb = File(stagedDir, DB_NAME)
            if (!importedDb.exists()) return@withContext fail("备份中未找到 $DB_NAME")
            val manifest = readManifest(File(stagedDir, MANIFEST_FILE))
            val validation = validateAndNormalize(importedDb)
            AppLogger.i(
                TAG,
                "导入校验: source=$sourceType, format=${manifest?.formatVersion ?: "legacy"}, " +
                    "db=${validation.databaseVersion}, integrity=${validation.integrityResult}"
            )
            if (validation.integrityResult != "ok") return@withContext fail("数据库完整性校验失败")
            if (validation.databaseVersion !in 1..AppDatabase.SCHEMA_VERSION) {
                return@withContext fail(
                    if (validation.databaseVersion > AppDatabase.SCHEMA_VERSION) "数据库版本较新，请先升级应用"
                    else "数据库版本无效"
                )
            }
            BackupImportPolicy.validateManifest(
                manifest = manifest,
                actualDatabaseVersion = validation.databaseVersion,
                currentFormatVersion = FORMAT_VERSION,
                currentDatabaseVersion = AppDatabase.SCHEMA_VERSION
            )?.let { error ->
                return@withContext fail(error)
            }
            AppLogger.i(
                TAG,
                "远程连接保留=${validation.remoteConnectionCount}, 状态重置=${validation.resetCount}, " +
                    "migration=${validation.databaseVersion}->${AppDatabase.SCHEMA_VERSION}"
            )

            val activeDb = AppDatabase.getInstance(context).openHelper.writableDatabase
            createSnapshot(activeDb, currentBackup)
            val targetSettings = File(context.filesDir, "datastore/$DATASTORE_FILE")
            hadOriginalSettings = targetSettings.exists()
            if (hadOriginalSettings) targetSettings.copyTo(settingsBackup, overwrite = true)

            AppLogger.i(TAG, "关闭 Room 并替换已校验数据库")
            AppDatabase.closeInstance()
            databaseClosed = true
            replaceDatabase(context, importedDb)
            notifyImportStage(stageObserver, ImportStage.DATABASE_REPLACED)
            restoreStagedSettings(stagedDir, targetSettings)
            notifyImportStage(stageObserver, ImportStage.SETTINGS_RESTORED)
            val keyStoreManager = KeyStoreManager(context)
            validation.remoteConnectionIds.forEach(keyStoreManager::deleteCredential)
            AppLogger.i(TAG, "已清除 ${validation.remoteConnectionIds.size} 个导入连接的本机凭据别名")

            AppLogger.i(TAG, "导入成功，即将重启进程")
            _events.tryEmit(ExportEvent.Success("导入成功"))
            Process.killProcess(Process.myPid())
            exitProcess(0)
        } catch (e: Exception) {
            AppLogger.e(TAG, "导入失败", e)
            if (databaseClosed) rollback(context, currentBackup, settingsBackup, hadOriginalSettings)
            fail("导入失败: ${e.message}")
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun createSnapshot(database: androidx.sqlite.db.SupportSQLiteDatabase, output: File) {
        output.parentFile?.mkdirs()
        output.delete()
        val escapedPath = output.absolutePath.replace("'", "''")
        database.execSQL("VACUUM INTO '$escapedPath'")
        check(output.exists() && output.length() >= 4096) { "无法创建一致性数据库快照" }
        AppLogger.d(TAG, "一致性快照完成: ${output.length()} bytes")
    }

    private fun validateAndNormalize(dbFile: File): ImportValidation {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        return try {
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use {
                if (it.moveToFirst()) it.getString(0) else "no_result"
            }
            val version = db.rawQuery("PRAGMA user_version", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            var remoteCount = 0
            var resetCount = 0
            var remoteIds = emptyList<Long>()
            if (integrity == "ok" && tableExists(db, "remote_connections")) {
                remoteIds = db.rawQuery("SELECT id FROM remote_connections", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getLong(0))
                    }
                }
                remoteCount = db.rawQuery("SELECT COUNT(*) FROM remote_connections", null).use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
                db.execSQL("UPDATE remote_connections SET status = 'DISCONNECTED' WHERE status != 'DISCONNECTED'")
                resetCount = db.rawQuery("SELECT changes()", null).use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
            }
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            ImportValidation(version, integrity, remoteCount, resetCount, remoteIds)
        } finally {
            db.close()
        }
    }

    private fun replaceDatabase(context: Context, importedDb: File) {
        val target = context.getDatabasePath(DB_NAME)
        target.parentFile?.mkdirs()
        File(target.parentFile, "$DB_NAME-wal").delete()
        File(target.parentFile, "$DB_NAME-shm").delete()
        val pending = File(target.parentFile, "$DB_NAME.importing")
        importedDb.copyTo(pending, overwrite = true)
        if (target.exists() && !target.delete()) error("无法移除当前数据库")
        if (!pending.renameTo(target)) {
            pending.copyTo(target, overwrite = true)
            pending.delete()
        }
    }

    private fun restoreStagedSettings(stagedDir: File, target: File) {
        val staged = listOf(
            File(stagedDir, "settings/$DATASTORE_FILE"),
            File(stagedDir, DATASTORE_FILE)
        ).firstOrNull(File::exists) ?: run {
            AppLogger.d(TAG, "旧备份不含 DataStore，保留当前设置")
            return
        }
        target.parentFile?.mkdirs()
        staged.copyTo(target, overwrite = true)
        AppLogger.i(TAG, "DataStore 设置已恢复: ${target.length()} bytes")
    }

    private fun notifyImportStage(observer: ImportStageObserver, stage: ImportStage) {
        AppLogger.d(TAG, "导入阶段完成: $stage")
        observer.onStage(stage)
    }

    private fun rollback(
        context: Context,
        dbBackup: File,
        settingsBackup: File,
        hadOriginalSettings: Boolean
    ) {
        AppLogger.w(TAG, "开始回滚导入")
        try {
            if (!dbBackup.exists()) error("一致性回滚快照不存在")
            replaceDatabase(context, dbBackup)
            val target = File(context.filesDir, "datastore/$DATASTORE_FILE")
            if (hadOriginalSettings && settingsBackup.exists()) {
                target.parentFile?.mkdirs()
                settingsBackup.copyTo(target, overwrite = true)
            } else if (!hadOriginalSettings) {
                target.delete()
            }
            AppLogger.i(TAG, "导入回滚成功")
        } catch (rollbackError: Exception) {
            AppLogger.e(TAG, "导入回滚失败", rollbackError)
        }
    }

    private fun queryCount(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Int =
        if (!tableExists(db, table)) 0 else db.query("SELECT COUNT(*) FROM $table").use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    private fun tableExists(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun addText(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun readManifest(file: File): BackupManifest? =
        if (!file.exists()) null else BackupManifest.fromJson(JSONObject(file.readText(Charsets.UTF_8)))

    private fun createWorkDir(context: Context, prefix: String): File =
        File(context.cacheDir, "database-transfer/$prefix-${UUID.randomUUID()}").apply { mkdirs() }

    private fun fail(message: String): Boolean {
        AppLogger.w(TAG, message)
        _events.tryEmit(ExportEvent.Error(message))
        return false
    }
}

internal data class BackupManifest(
    val formatVersion: Int,
    val databaseVersion: Int,
    val createdAt: Long,
    val includesRemoteConnections: Boolean,
    val remoteConnectionCount: Int
) {
    fun toJson(): JSONObject = JSONObject()
        .put("formatVersion", formatVersion)
        .put("databaseVersion", databaseVersion)
        .put("createdAt", createdAt)
        .put("includesRemoteConnections", includesRemoteConnections)
        .put("remoteConnectionCount", remoteConnectionCount)

    companion object {
        fun fromJson(json: JSONObject) = BackupManifest(
            formatVersion = json.getInt("formatVersion"),
            databaseVersion = json.getInt("databaseVersion"),
            createdAt = json.getLong("createdAt"),
            includesRemoteConnections = json.optBoolean("includesRemoteConnections", false),
            remoteConnectionCount = json.optInt("remoteConnectionCount", 0)
        )
    }
}

private data class ImportValidation(
    val databaseVersion: Int,
    val integrityResult: String,
    val remoteConnectionCount: Int,
    val resetCount: Int,
    val remoteConnectionIds: List<Long>
)

sealed class ExportEvent {
    data class Success(val message: String) : ExportEvent()
    data class Error(val message: String) : ExportEvent()
}

internal enum class ImportStage {
    DATABASE_REPLACED,
    SETTINGS_RESTORED
}

internal fun interface ImportStageObserver {
    fun onStage(stage: ImportStage)

    companion object {
        val NONE = ImportStageObserver { }
    }
}
