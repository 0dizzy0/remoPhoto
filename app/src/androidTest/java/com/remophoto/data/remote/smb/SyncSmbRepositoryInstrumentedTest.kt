package com.remophoto.data.remote.smb

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.request.ErrorResult
import com.remophoto.data.local.AppDatabase
import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.local.entity.RepositoryEntity
import com.remophoto.data.remote.RemoteDataException
import com.remophoto.data.remote.RemoteErrorCategory
import com.remophoto.data.security.CredentialStore
import com.remophoto.domain.usecase.SyncSmbRepositoryUseCase
import com.remophoto.util.AppLogger
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncSmbRepositoryInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var fixture: MutableTreeBackend
    private lateinit var useCase: SyncSmbRepositoryUseCase
    private lateinit var sessionManager: SmbSessionManager
    private lateinit var connection: RemoteConnectionEntity
    private var repositoryId: Long = 0L

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() = runBlocking {
        AppLogger.logcatEnabled = false
        AppLogger.fileLogEnabled = false
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val connectionId = database.remoteConnectionDao().insert(
            RemoteConnectionEntity(
                type = RemoteType.SMB,
                host = "fixture",
                port = 445,
                displayName = "fixture",
                shareName = "share",
                username = "user",
                rootPath = "photos",
                addedTime = 1L,
                status = ConnectionStatus.CONNECTED,
            )
        )
        connection = checkNotNull(database.remoteConnectionDao().getConnectionById(connectionId))
        repositoryId = database.repositoryDao().insert(
            RepositoryEntity(
                uriString = "remote://connection/$connectionId",
                path = null,
                name = "fixture",
                remoteConnectionId = connectionId,
                addedTime = 1L,
            )
        )
        fixture = MutableTreeBackend(initialTree())
        sessionManager = SmbSessionManager(TestCredentialStore(), fixture)
        useCase = SyncSmbRepositoryUseCase(
            context = context,
            database = database,
            scanner = SmbCatalogScanner(sessionManager),
            albumDao = database.albumDao(),
            imageDao = database.imageDao(),
            repositoryDao = database.repositoryDao(),
            connectionDao = database.remoteConnectionDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun successfulRefreshKeepsStableAlbumsAndAppliesRemoteDeletion() = runBlocking {
        val first = useCase.execute(connection, repositoryId)
        val firstAlbums = database.albumDao().getAlbumsByRepositoryList(repositoryId)
        val rootId = firstAlbums.single { SmbPathCodec.parseAlbumStorageValue(it.directoryPath)?.relativeDirectory == "" }.id
        assertEquals(2, first.imageCount)

        fixture.tree.set(
            mapOf(
                "photos" to listOf(testDirectory("child")),
                "photos\\child" to listOf(testFile("new.webp", 30, 300)),
            )
        )
        val second = useCase.execute(connection, repositoryId)
        val secondAlbums = database.albumDao().getAlbumsByRepositoryList(repositoryId)
        val secondRootId = secondAlbums.single { SmbPathCodec.parseAlbumStorageValue(it.directoryPath)?.relativeDirectory == "" }.id
        val images = database.imageDao().getImagesByRepository(repositoryId)

        assertEquals(1, second.imageCount)
        assertEquals(rootId, secondRootId)
        assertEquals(listOf("new.webp"), images.map { it.fileName })
        assertTrue(images.single().filePath.startsWith("smb-media://"))
        assertEquals(1, database.repositoryDao().getRepositoryById(repositoryId)?.imageCount)
    }

    @Test
    fun failedRefreshPreservesPreviousCompleteIndex() = runBlocking {
        useCase.execute(connection, repositoryId)
        val before = database.imageDao().getImagesByRepository(repositoryId).map { it.filePath }
        fixture.failure.set(RemoteDataException(RemoteErrorCategory.HOST_UNREACHABLE, "fixture"))

        val error = runCatching { useCase.execute(connection, repositoryId) }.exceptionOrNull()
        val after = database.imageDao().getImagesByRepository(repositoryId).map { it.filePath }

        assertTrue(error is RemoteDataException)
        assertEquals(before, after)
        assertEquals(ConnectionStatus.ERROR, database.remoteConnectionDao().getConnectionById(connection.id)?.status)
    }

    @Test
    fun coilDecodesSmbStreamAndReleasesAllResources() = runBlocking {
        useCase.execute(connection, repositoryId)
        val image = database.imageDao().getImagesByRepository(repositoryId).first { it.fileName == "root.jpg" }
        val resolver = SmbMediaResolver(
            database.imageDao(),
            database.albumDao(),
            database.remoteConnectionDao(),
        )
        val loader = ImageLoader.Builder(context)
            .components { add(SmbImageFetcher.Factory(resolver, sessionManager)) }
            .build()

        val result = loader.execute(
            ImageRequest.Builder(context)
                .data(image.filePath)
                .size(32)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
        )

        if (result is ErrorResult) {
            throw AssertionError("Coil SMB decode failed: ${result.throwable.javaClass.simpleName}", result.throwable)
        }
        assertTrue(result is SuccessResult)
        assertEquals(0, sessionManager.activeCount())
    }

    @Test
    fun coilGifDecoderReadsSmbStreamAndReleasesAllResources() = runBlocking {
        useCase.execute(connection, repositoryId)
        val image = database.imageDao().getImagesByRepository(repositoryId).first { it.fileName == "old.gif" }
        val resolver = SmbMediaResolver(
            database.imageDao(),
            database.albumDao(),
            database.remoteConnectionDao(),
        )
        val loader = ImageLoader.Builder(context)
            .components {
                add(SmbImageFetcher.Factory(resolver, sessionManager))
                add(GifDecoder.Factory())
            }
            .build()

        val result = loader.execute(
            ImageRequest.Builder(context)
                .data(image.filePath)
                .size(32)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
        )

        if (result is ErrorResult) {
            throw AssertionError("Coil SMB GIF decode failed: ${result.throwable.javaClass.simpleName}", result.throwable)
        }
        assertTrue(result is SuccessResult)
        assertEquals(0, sessionManager.activeCount())
    }

    private fun initialTree() = mapOf(
        "photos" to listOf(testFile("root.jpg", 10, 100), testDirectory("child")),
        "photos\\child" to listOf(testFile("old.gif", 20, 200)),
    )
}

private class MutableTreeBackend(
    initial: Map<String, List<SmbDirectoryEntry>>,
) : SmbSessionBackend {
    val tree = AtomicReference(initial)
    val failure = AtomicReference<Throwable?>(null)

    override fun open(connection: RemoteConnectionEntity, credential: CharArray): SmbShareSession =
        object : SmbShareSession {
            override val dialect = "SMB_3_1_1"
            override val signingRequired = true
            override fun list(path: String): List<SmbDirectoryEntry> {
                failure.get()?.let { throw it }
                return tree.get()[path] ?: error("unexpected path")
            }

            override fun openReadOnly(path: String): SmbFileHandle = object : SmbFileHandle {
                override val inputStream = ByteArrayInputStream(
                    if (path.endsWith(".gif", ignoreCase = true)) TEST_GIF else TEST_PNG
                )
                override fun close() = inputStream.close()
            }

            override fun close() = Unit
        }
}

private val TEST_PNG: ByteArray = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)

private val TEST_GIF: ByteArray = Base64.getDecoder().decode(
    "R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="
)

private class TestCredentialStore : CredentialStore {
    override fun storeCredential(connectionId: Long, credential: CharArray) = Unit
    override fun getCredential(connectionId: Long): CharArray = "secret".toCharArray()
    override fun deleteCredential(connectionId: Long) = Unit
    override fun hasCredential(connectionId: Long) = true
}

private fun testFile(name: String, size: Long, modified: Long) =
    SmbDirectoryEntry(name, false, false, size, modified)

private fun testDirectory(name: String) = SmbDirectoryEntry(name, true, false, 0, 0)
