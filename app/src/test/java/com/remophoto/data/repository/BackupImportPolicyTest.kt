package com.remophoto.data.repository

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupImportPolicyTest {
    @Test
    fun `manifest accepts current versions and rejects invalid or mismatched versions`() {
        val current = manifest(formatVersion = 1, databaseVersion = 4)
        assertNull(BackupImportPolicy.validateManifest(current, 4, 1, 4))

        assertEquals(
            "备份格式较新，请先升级应用",
            BackupImportPolicy.validateManifest(
                manifest(formatVersion = 2, databaseVersion = 4),
                4,
                1,
                4
            )
        )
        assertEquals(
            "备份格式版本无效",
            BackupImportPolicy.validateManifest(
                manifest(formatVersion = 0, databaseVersion = 4),
                4,
                1,
                4
            )
        )
        assertEquals(
            "manifest 与数据库版本不一致",
            BackupImportPolicy.validateManifest(current, 3, 1, 4)
        )
    }

    @Test
    fun `legacy backup without manifest remains supported`() {
        assertNull(
            BackupImportPolicy.validateManifest(
                manifest = null,
                actualDatabaseVersion = 3,
                currentFormatVersion = 1,
                currentDatabaseVersion = 4
            )
        )
    }

    @Test
    fun `allowed archive entries are extracted into destination`() = withTempDirectory { root ->
        val archive = File(root, "valid.zip")
        createZip(
            archive,
            mapOf(
                BackupImportPolicy.DATABASE_FILE to "database",
                "settings/${BackupImportPolicy.DATASTORE_FILE}" to "settings",
                BackupImportPolicy.MANIFEST_FILE to "{}"
            )
        )
        val destination = File(root, "out")

        val entries = BackupImportPolicy.extractKnownEntries(archive, destination)

        assertEquals(
            listOf(
                BackupImportPolicy.DATABASE_FILE,
                "settings/${BackupImportPolicy.DATASTORE_FILE}",
                BackupImportPolicy.MANIFEST_FILE
            ),
            entries.map(ExtractedBackupEntry::name)
        )
        assertEquals("settings", File(destination, "settings/${BackupImportPolicy.DATASTORE_FILE}").readText())
    }

    @Test
    fun `unknown and traversal archive entries are rejected`() = withTempDirectory { root ->
        val unknown = File(root, "unknown.zip")
        createZip(unknown, mapOf("private.txt" to "secret"))
        assertThrows(IllegalArgumentException::class.java) {
            BackupImportPolicy.extractKnownEntries(unknown, File(root, "unknown-out"))
        }

        val traversal = File(root, "traversal.zip")
        createZip(traversal, mapOf("../${BackupImportPolicy.DATABASE_FILE}" to "database"))
        assertThrows(IllegalArgumentException::class.java) {
            BackupImportPolicy.extractKnownEntries(traversal, File(root, "traversal-out"))
        }
    }

    @Test
    fun `empty or malformed zip is rejected`() = withTempDirectory { root ->
        val empty = File(root, "empty.zip")
        ZipOutputStream(empty.outputStream()).use { }
        assertEquals(true, BackupImportPolicy.isZip(empty))
        assertThrows(IllegalArgumentException::class.java) {
            BackupImportPolicy.extractKnownEntries(empty, File(root, "empty-out"))
        }

        val malformed = File(root, "malformed.zip")
        malformed.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00))
        assertThrows(Exception::class.java) {
            BackupImportPolicy.extractKnownEntries(malformed, File(root, "malformed-out"))
        }
    }

    private fun manifest(formatVersion: Int, databaseVersion: Int) = BackupManifest(
        formatVersion = formatVersion,
        databaseVersion = databaseVersion,
        createdAt = 1,
        includesRemoteConnections = false,
        remoteConnectionCount = 0
    )

    private fun createZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("remophoto-backup-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
