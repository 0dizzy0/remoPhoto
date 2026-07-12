package com.remophoto.data.remote.smb

import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.remote.RemoteDataException
import com.remophoto.data.remote.RemoteErrorCategory
import com.remophoto.data.security.CredentialStore
import com.remophoto.util.AppLogger
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before

class SmbCatalogScannerTest {
    @Before
    fun disableAndroidLogging() {
        AppLogger.logcatEnabled = false
        AppLogger.fileLogEnabled = false
    }

    @Test
    fun `builds root hierarchy filters files and hides empty directories`() = runBlocking {
        val share = TreeShareSession(
            mapOf(
                "photos" to listOf(
                    file("root.JPG", 10, 100),
                    directory("empty"),
                    directory("父目录"),
                    directory("junction", reparse = true),
                    file("note.txt", 2, 1),
                ),
                "photos\\empty" to emptyList(),
                "photos\\父目录" to listOf(directory("子 目录")),
                "photos\\父目录\\子 目录" to listOf(file("动图.GIF", 20, 200)),
            )
        )
        val manager = manager(share)
        val spool = Files.createTempFile("smb-catalog", ".bin").toFile()

        val snapshot = SmbCatalogScanner(manager).scanToSpool(connection(), spool)

        assertEquals(2, snapshot.imageCount)
        assertEquals(3, snapshot.albums.size)
        assertEquals(listOf("", "父目录", "父目录/子 目录"), snapshot.albums.map { it.relativePath })
        assertEquals(listOf(1, 0, 1), snapshot.albums.map { it.imageCount })
        assertEquals(1, snapshot.skippedReparsePoints)
        val records = mutableListOf<SmbSpoolMediaRecord>()
        SmbCatalogScanner.readSpool(spool, records::add)
        assertEquals(listOf("root.JPG", "动图.GIF"), records.map { it.fileName })
        assertTrue(records.all { it.opaqueMediaKey.length == 64 })
        assertFalse(records.any { it.mimeType == "text/plain" })
        assertTrue(share.closed.get())
        spool.delete()
        Unit
    }

    @Test
    fun `resource limit fails and deletes partial spool`() = runBlocking {
        val share = TreeShareSession(
            mapOf("photos" to listOf(file("1.jpg", 1, 1), file("2.jpg", 1, 1)))
        )
        val spool = File(Files.createTempDirectory("smb-limit").toFile(), "scan.bin")

        val error = runCatching {
            SmbCatalogScanner(manager(share), SmbCatalogLimits(maxEntries = 1)).scanToSpool(connection(), spool)
        }.exceptionOrNull()

        assertEquals(RemoteErrorCategory.RESOURCE_LIMIT, (error as RemoteDataException).category)
        assertFalse(spool.exists())
    }

    @Test
    fun `spools ten thousand images without retaining media records`() = runBlocking {
        val entries = List(10_001) { index -> file("image-$index.jpg", index.toLong(), index.toLong()) }
        val share = TreeShareSession(mapOf("photos" to entries))
        val spool = Files.createTempFile("smb-large-catalog", ".bin").toFile()

        val snapshot = SmbCatalogScanner(manager(share)).scanToSpool(connection(), spool)
        var recordCount = 0
        SmbCatalogScanner.readSpool(spool) { recordCount++ }

        assertEquals(10_001, snapshot.imageCount)
        assertEquals(1, snapshot.albums.size)
        assertEquals(10_001, recordCount)
        assertTrue(spool.length() > 0L)
        spool.delete()
        Unit
    }

    private fun manager(share: SmbShareSession) = SmbSessionManager(
        credentialStore = FixedCredentialStore(),
        backend = SmbSessionBackend { _, _ -> share },
    )

    private fun connection() = RemoteConnectionEntity(
        id = 7L,
        type = RemoteType.SMB,
        host = "server",
        port = 445,
        displayName = "测试仓库",
        shareName = "share",
        username = "alice",
        rootPath = "photos",
        addedTime = 1L,
    )

    private fun file(name: String, size: Long, modified: Long) =
        SmbDirectoryEntry(name, false, false, size, modified)

    private fun directory(name: String, reparse: Boolean = false) =
        SmbDirectoryEntry(name, true, reparse, 0, 0)
}

private class FixedCredentialStore : CredentialStore {
    override fun storeCredential(connectionId: Long, credential: CharArray) = Unit
    override fun getCredential(connectionId: Long): CharArray = "secret".toCharArray()
    override fun deleteCredential(connectionId: Long) = Unit
    override fun hasCredential(connectionId: Long): Boolean = true
}

private class TreeShareSession(
    private val entriesByPath: Map<String, List<SmbDirectoryEntry>>,
) : SmbShareSession {
    val closed = AtomicBoolean(false)
    override val dialect = "SMB_3_1_1"
    override val signingRequired = true

    override fun list(path: String): List<SmbDirectoryEntry> =
        entriesByPath[path] ?: error("unexpected path")

    override fun openReadOnly(path: String): SmbFileHandle = object : SmbFileHandle {
        override val inputStream = ByteArrayInputStream(byteArrayOf())
        override fun close() = inputStream.close()
    }

    override fun close() {
        closed.set(true)
    }
}
