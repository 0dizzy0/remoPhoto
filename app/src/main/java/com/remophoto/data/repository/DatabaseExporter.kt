package com.remophoto.data.repository

import android.content.Context
import android.net.Uri
import android.os.Process
import com.remophoto.data.local.AppDatabase
import com.remophoto.util.AppLogger
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
 * 将 Room 数据库文件打包为 ZIP 通过 SAF 导出/导入。
 * 导入前自动备份当前数据库，验证失败可回滚。
 *
 * 用法：
 * - exportDatabase(context, destUri)：导出到 SAF 目标 URI
 * - importDatabase(context, sourceUri)：从 SAF 源 URI 导入
 */
object DatabaseExporter {

    private const val TAG = "DatabaseExporter"
    private const val DB_NAME = "remophoto.db"

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
                ?: return logAndReturnFalse("数据库目录不存在")

            val dbFiles = listOfNotNull(
                File(dbDir, DB_NAME),
                File(dbDir, "$DB_NAME-wal").takeIf { it.exists() },
                File(dbDir, "$DB_NAME-shm").takeIf { it.exists() }
            )

            AppLogger.i(TAG, "开始导出数据库: ${dbFiles.size} 个文件 → $destUri")

            context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
                ZipOutputStream(outputStream).use { zipOut ->
                    for (file in dbFiles) {
                        if (!file.exists()) continue
                        zipOut.putNextEntry(ZipEntry(file.name))
                        FileInputStream(file).use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                        AppLogger.d(TAG, "已打包: ${file.name} (${file.length()} bytes)")
                    }
                }
            } ?: return logAndReturnFalse("无法打开输出流")

            AppLogger.i(TAG, "数据库导出成功")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "数据库导出失败", e)
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
                ?: return logAndReturnFalse("数据库目录不存在")

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

            AppLogger.i(TAG, "数据库导入成功，备份已清理。即将重启进程以加载新数据库。")

            // 重启 App 进程，让 Room 重新初始化并使用导入的数据库
            Process.killProcess(Process.myPid())
            // killProcess 后正常不会执行到这里，exitProcess 作为兜底
            exitProcess(0)

            @Suppress("UNREACHABLE_CODE")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "数据库导入失败", e)
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

    private fun logAndReturnFalse(msg: String): Boolean {
        AppLogger.w(TAG, msg)
        return false
    }
}
