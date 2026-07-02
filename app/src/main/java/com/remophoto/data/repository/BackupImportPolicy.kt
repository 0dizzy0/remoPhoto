package com.remophoto.data.repository

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/** 可由 JVM 测试覆盖的备份格式、条目白名单和路径安全规则。 */
internal object BackupImportPolicy {
    const val DATABASE_FILE = "remophoto.db"
    const val DATASTORE_FILE = "settings.preferences_pb"
    const val MANIFEST_FILE = "manifest.json"

    private val allowedZipEntries = setOf(
        DATABASE_FILE,
        "$DATABASE_FILE-wal",
        "$DATABASE_FILE-shm",
        DATASTORE_FILE,
        "settings/$DATASTORE_FILE",
        MANIFEST_FILE
    )

    fun validateManifest(
        manifest: BackupManifest?,
        actualDatabaseVersion: Int,
        currentFormatVersion: Int,
        currentDatabaseVersion: Int
    ): String? {
        if (manifest == null) return null
        if (manifest.formatVersion !in 1..currentFormatVersion) {
            return if (manifest.formatVersion > currentFormatVersion) {
                "备份格式较新，请先升级应用"
            } else {
                "备份格式版本无效"
            }
        }
        if (manifest.databaseVersion !in 1..currentDatabaseVersion) {
            return if (manifest.databaseVersion > currentDatabaseVersion) {
                "数据库版本较新，请先升级应用"
            } else {
                "manifest 数据库版本无效"
            }
        }
        if (manifest.databaseVersion != actualDatabaseVersion) {
            return "manifest 与数据库版本不一致"
        }
        return null
    }

    fun isZip(file: File): Boolean = FileInputStream(file).use { input ->
        val signature = ByteArray(4)
        if (input.read(signature) != 4 || signature[0] != 0x50.toByte() || signature[1] != 0x4B.toByte()) {
            return@use false
        }
        val marker =
            ((signature[2].toInt() and 0xFF) shl 8) or (signature[3].toInt() and 0xFF)
        marker in setOf(0x0304, 0x0506, 0x0708)
    }

    fun extractKnownEntries(zipFile: File, destination: File): List<ExtractedBackupEntry> {
        val extracted = mutableListOf<ExtractedBackupEntry>()
        val seen = mutableSetOf<String>()
        val root = destination.canonicalFile
        ZipInputStream(FileInputStream(zipFile).buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val normalized = entry.name.replace('\\', '/')
                    require(normalized in allowedZipEntries) {
                        "备份包含不允许的文件: ${entry.name}"
                    }
                    require(".." !in normalized.split('/')) { "检测到路径穿越" }
                    require(seen.add(normalized)) { "备份包含重复文件: $normalized" }

                    val output = File(destination, normalized)
                    require(output.canonicalFile.toPath().startsWith(root.toPath())) {
                        "检测到路径穿越"
                    }
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use(zip::copyTo)
                    extracted += ExtractedBackupEntry(normalized, output.length())
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        require(extracted.isNotEmpty()) { "备份 ZIP 为空或损坏" }
        return extracted
    }
}

internal data class ExtractedBackupEntry(val name: String, val size: Long)
