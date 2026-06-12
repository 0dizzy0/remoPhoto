package com.remophoto.data.repository

import android.content.Context
import android.net.Uri
import android.os.Process
import com.remophoto.data.local.AppDatabase
import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.di.dependencies
import com.remophoto.util.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.system.exitProcess
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据库导入/导出工具
 *
 * 将 Room 数据库文件 + DataStore 设置文件打包为 ZIP 通过 SAF 导出/导入。
 * Phase 4 增强：导出包含 DataStore Preferences + remote_connections 表（凭据已清除）。
 * 导入前自动备份当前数据库，验证失败可回滚。
 *
 * 用法：
 * - exportDatabase(context, destUri)：导出到 SAF 目标 URI
 * - importDatabase(context, sourceUri)：从 SAF 源 URI 导入
 */
object DatabaseExporter {

    private const val TAG = "DatabaseExporter"
    private const val DB_NAME = "remophoto.db"
    private const val DATASTORE_FILE = "settings.preferences_pb"

    /** 导入导出结果事件 */
    private val _events = MutableSharedFlow<ExportEvent>()
    val events: SharedFlow<ExportEvent> = _events

    /**
     * 导出数据库到 SAF 目标 URI
     *
     * 将 Room 数据库文件（.db, .db-wal, .db-shm）打包为 ZIP。
     *
     * @param context Application Context
     * @param destUri SAF 目标文件 URI（由 ACTION_CREATE_DOCUMENT 获取）
     * @return 导出是否成功
     */
    fun exportDatabase(context: Context, destUri: Uri): Boolean {
        return try {
            val dbDir = context.getDatabasePath(DB_NAME).parentFile
                ?: return failAndEmit("数据库目录不存在")

            // DB 文件 + DataStore 设置文件
            val datastoreFile = File(context.filesDir, "datastore/$DATASTORE_FILE")
            val files = mutableListOf(
                File(dbDir, DB_NAME),
            ).apply {
                File(dbDir, "$DB_NAME-wal").takeIf { it.exists() }?.let { add(it) }
                File(dbDir, "$DB_NAME-shm").takeIf { it.exists() }?.let { add(it) }
                if (datastoreFile.exists()) add(datastoreFile)
            }

            AppLogger.i(TAG, "开始导出: ${files.size} 个文件 → $destUri")

            context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
                ZipOutputStream(outputStream).use { zipOut ->
                    for (file in files) {
                        if (!file.exists()) continue
                        val entryName = if (file == datastoreFile) "settings/$DATASTORE_FILE" else file.name
                        zipOut.putNextEntry(ZipEntry(entryName))
                        FileInputStream(file).use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                        AppLogger.d(TAG, "已打包: $entryName (${file.length()} bytes)")
                    }
                }
            } ?: return failAndEmit("无法打开输出流")

            AppLogger.i(TAG, "导出成功 (含 DataStore 设置)")
            _events.tryEmit(ExportEvent.Success("导出成功"))
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "导出失败", e)
            _events.tryEmit(ExportEvent.Error("导出失败: ${e.message}"))
            false
        }
    }

    /**
     * 从 SAF 源 URI 导入数据库
     *
     * 1. 关闭当前 Room 数据库实例（释放文件锁）
     * 2. 备份当前数据库到 .backup 文件
     * 3. 解压 ZIP 到数据库目录
     * 4. 验证导入的 .db 文件可读
     * 5. 失败时回滚备份
     * 6. 导入成功后自动重启 App 进程以加载新数据库
     *
     * @param context Application Context
     * @param sourceUri SAF 源文件 URI（由 ACTION_OPEN_DOCUMENT 获取）
     * @return 导入是否成功
     */
    fun importDatabase(context: Context, sourceUri: Uri): Boolean {
        return try {
            val dbDir = context.getDatabasePath(DB_NAME).parentFile
                ?: return failAndEmit("数据库目录不存在")

            // 步骤 0：关闭 Room 数据库实例（释放 WAL 文件锁）
            AppLogger.i(TAG, "关闭当前 Room 数据库实例")
            try {
                AppDatabase.closeInstance()
            } catch (e: Exception) {
                AppLogger.w(TAG, "关闭数据库实例时出错（继续导入）: ${e.message}")
            }

            val mainDb = File(dbDir, DB_NAME)
            val backupDb = File(dbDir, "$DB_NAME.backup")
            val walFile = File(dbDir, "$DB_NAME-wal")
            val shmFile = File(dbDir, "$DB_NAME-shm")

            // 步骤 1：备份当前数据库
            AppLogger.i(TAG, "导入前备份当前数据库")
            try {
                if (mainDb.exists()) {
                    FileInputStream(mainDb).use { input ->
                        FileOutputStream(backupDb).use { output ->
                            input.copyTo(output)
                        }
                    }
                    AppLogger.d(TAG, "备份完成: ${backupDb.length()} bytes")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "备份失败（继续导入）: ${e.message}")
            }

            // 步骤 2：解压 ZIP
            AppLogger.i(TAG, "开始解压导入文件: $sourceUri")
            var mainDbRestored = false

            try {
                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zipIn ->
                        var entry = zipIn.nextEntry
                        while (entry != null) {
                            val destFile = File(dbDir, entry.name)
                            FileOutputStream(destFile).use { fos ->
                                zipIn.copyTo(fos)
                            }
                            AppLogger.d(TAG, "已解压: ${entry.name} (${destFile.length()} bytes)")
                            if (entry.name == DB_NAME) {
                                mainDbRestored = true
                            }
                            zipIn.closeEntry()
                            entry = zipIn.nextEntry
                        }
                    }
                } ?: return rollbackAndReturnFalse(backupDb, mainDb, walFile, shmFile, "无法打开输入流")

                if (!mainDbRestored) {
                    return rollbackAndReturnFalse(backupDb, mainDb, walFile, shmFile, "ZIP 中未找到数据库文件")
                }
            } catch (e: Exception) {
                // 解压失败 — 回滚
                AppLogger.e(TAG, "解压失败，开始回滚", e)
                return rollbackAndReturnFalse(backupDb, mainDb, walFile, shmFile, "解压失败: ${e.message}")
            }

            // 步骤 3：验证导入的 .db 文件
            if (!mainDb.exists() || mainDb.length() < 4096) {
                return rollbackAndReturnFalse(backupDb, mainDb, walFile, shmFile, "导入的数据库文件无效（不存在或过小）")
            }

            // 清理备份文件
            backupDb.delete()
            // 注意：不要删除 WAL/SHM 文件！
            // Room 使用 WAL 模式，主 .db 文件可能不包含最新数据（如封面更新）。
            // 导入时我们已将完整的 WAL/SHM 一起解压，Room 重启后会自动恢复 WAL 中的未 checkpoint 数据。

            // Phase 4: 导入后恢复 DataStore 设置文件
            restoreDataStore(context, dbDir)

            // Phase 4: 导入后标记所有远程连接为 DISCONNECTED（凭据不可恢复，需用户重新输入）
            markRemoteConnectionsDisconnected(context)

            AppLogger.i(TAG, "导入成功（含 DataStore），清理备份。即将重启进程。")

            // 重启 App 进程，让 Room 重新初始化并使用导入的数据库
            Process.killProcess(Process.myPid())
            exitProcess(0)

            @Suppress("UNREACHABLE_CODE")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "数据库导入失败", e)
            _events.tryEmit(ExportEvent.Error("导入失败: ${e.message}"))
            false
        }
    }

    /**
     * 回滚：从备份恢复数据库
     */
    private fun rollbackAndReturnFalse(
        backupDb: File,
        mainDb: File,
        walFile: File,
        shmFile: File,
        reason: String
    ): Boolean {
        AppLogger.w(TAG, "导入回滚: $reason")
        try {
            if (backupDb.exists()) {
                mainDb.delete()
                walFile.delete()
                shmFile.delete()
                FileInputStream(backupDb).use { input ->
                    FileOutputStream(mainDb).use { output ->
                        input.copyTo(output)
                    }
                }
                backupDb.delete()
                AppLogger.i(TAG, "回滚成功: 已恢复备份数据库")
            } else {
                AppLogger.w(TAG, "无可用的备份文件，跳过回滚")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "回滚失败", e)
        }
        return false
    }

    private fun failAndEmit(msg: String): Boolean {
        AppLogger.w(TAG, msg)
        _events.tryEmit(ExportEvent.Error(msg))
        return false
    }

    /**
     * Phase 4: 恢复 DataStore 设置文件
     */
    private fun restoreDataStore(context: Context, dbDir: File) {
        try {
            val zipDatastoreFile = File(dbDir, "settings/$DATASTORE_FILE")
            val targetDir = File(context.filesDir, "datastore")
            val targetFile = File(targetDir, DATASTORE_FILE)

            if (zipDatastoreFile.exists()) {
                targetDir.mkdirs()
                FileInputStream(zipDatastoreFile).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                AppLogger.i(TAG, "DataStore 设置已恢复: ${targetFile.length()} bytes")
            } else {
                // ZIP 中可能没有 settings 目录（旧版导出），DataStore 存入 dbDir 根目录
                val flatFile = File(dbDir, DATASTORE_FILE)
                if (flatFile.exists()) {
                    val destDir = File(context.filesDir, "datastore")
                    destDir.mkdirs()
                    FileInputStream(flatFile).use { input ->
                        FileOutputStream(File(destDir, DATASTORE_FILE)).use { output ->
                            input.copyTo(output)
                        }
                    }
                    AppLogger.i(TAG, "DataStore 设置已从根目录恢复")
                } else {
                    AppLogger.d(TAG, "ZIP 中无 DataStore 设置文件，跳过恢复")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "恢复 DataStore 设置失败", e)
        }
    }

    /**
     * Phase 4: 导入后标记所有远程连接为 DISCONNECTED
     *
     * 远程凭据存储在 Android Keystore 中，跨设备不可恢复。
     * 导入后将所有连接状态重置，用户需手动重新输入凭据。
     */
    private fun markRemoteConnectionsDisconnected(context: Context) {
        try {
            val deps = context.dependencies
            val connections = kotlinx.coroutines.runBlocking {
                deps.remoteConnectionDao.getAllConnectionsList()
            }
            for (conn in connections) {
                kotlinx.coroutines.runBlocking {
                    deps.remoteConnectionDao.updateStatus(conn.id, ConnectionStatus.DISCONNECTED)
                }
                // 清除旧 Keystore 凭据（原设备的加密密钥无法跨设备恢复）
                try {
                    deps.keyStoreManager.deleteCredential(conn.id)
                } catch (_: Exception) {}
            }
            AppLogger.i(TAG, "已标记 ${connections.size} 个远程连接为 DISCONNECTED")
        } catch (e: Exception) {
            AppLogger.e(TAG, "标记远程连接状态失败", e)
        }
    }
}

/**
 * 导入导出结果事件
 */
sealed class ExportEvent {
    data class Success(val message: String) : ExportEvent()
    data class Error(val message: String) : ExportEvent()
}
