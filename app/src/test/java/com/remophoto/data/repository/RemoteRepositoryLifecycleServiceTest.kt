package com.remophoto.data.repository

import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.security.CredentialStore
import kotlinx.coroutines.test.runTest
import com.remophoto.util.AppLogger
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRepositoryLifecycleServiceTest {
    @Before
    fun disableAndroidLogging() {
        AppLogger.logcatEnabled = false
        AppLogger.fileLogEnabled = false
    }

    @Test
    fun `saves SMB metadata credential and clears caller secret`() = runTest {
        val metadata = FakeMetadataStore()
        val credentials = FakeCredentialStore()
        val sessions = FakeSessionInvalidator()
        val service = RemoteRepositoryLifecycleService(metadata, credentials, sessions)
        val secret = "secret".toCharArray()

        val created = service.saveTested(smbConfig(), secret)

        assertEquals(41L, created.connectionId)
        assertEquals("secret", credentials.values[41L]?.concatToString())
        assertEquals(ConnectionStatus.CONNECTED, metadata.statuses[41L])
        assertTrue(secret.all { it == '\u0000' })
    }

    @Test
    fun `credential failure rolls back metadata and invalidates session`() = runTest {
        val metadata = FakeMetadataStore()
        val credentials = FakeCredentialStore(failStore = true)
        val sessions = FakeSessionInvalidator()
        val service = RemoteRepositoryLifecycleService(metadata, credentials, sessions)

        val error = runCatching {
            service.saveTested(smbConfig(), "secret".toCharArray())
        }.exceptionOrNull()

        assertTrue(error is SecurityException)
        assertEquals(listOf(31L), metadata.removedRepositories)
        assertEquals(listOf(41L), sessions.invalidated)
    }

    @Test
    fun `marks restored SMB metadata without credential as auth required`() = runTest {
        val connection = smbConnection(id = 9L)
        val metadata = FakeMetadataStore(smbConnections = listOf(connection))
        val service = RemoteRepositoryLifecycleService(
            metadata,
            FakeCredentialStore(),
            FakeSessionInvalidator(),
        )

        val count = service.recoverMissingCredentials()

        assertEquals(1, count)
        assertEquals(ConnectionStatus.AUTH_REQUIRED, metadata.statuses[9L])
    }

    @Test
    fun `removes metadata even when external credential cleanup must retry`() = runTest {
        val metadata = FakeMetadataStore().apply { removeConnectionId = 12L }
        val service = RemoteRepositoryLifecycleService(
            metadata,
            FakeCredentialStore(failDelete = true),
            FakeSessionInvalidator(),
        )

        val result = service.remove(31L)

        assertTrue(result.removed)
        assertTrue(result.externalCleanupPending)
        assertEquals(listOf(31L), metadata.removedRepositories)
    }

    @Test
    fun `prepares work cancellation and completes cache cleanup around metadata removal`() = runTest {
        val metadata = FakeMetadataStore().apply { removeConnectionId = 12L }
        val cleaner = FakeExternalCleaner()
        val service = RemoteRepositoryLifecycleService(
            metadata,
            FakeCredentialStore(),
            FakeSessionInvalidator(),
            cleaner,
        )

        val result = service.remove(31L)

        assertTrue(result.removed)
        assertFalse(result.externalCleanupPending)
        assertEquals(listOf(31L), cleaner.prepared)
        assertEquals(listOf(31L), cleaner.completed)
    }

    private fun smbConfig() = RemoteRepositoryConfig(
        type = RemoteType.SMB,
        host = "server",
        port = 445,
        displayName = "photos",
        shareName = "share",
        username = "alice",
    )

    private fun smbConnection(id: Long) = RemoteConnectionEntity(
        id = id,
        type = RemoteType.SMB,
        host = "server",
        port = 445,
        displayName = "photos",
        shareName = "share",
        username = "alice",
        addedTime = 1L,
    )
}

private class FakeMetadataStore(
    private val smbConnections: List<RemoteConnectionEntity> = emptyList(),
) : RemoteMetadataStore {
    val statuses = mutableMapOf<Long, ConnectionStatus>()
    val removedRepositories = mutableListOf<Long>()
    var removeConnectionId: Long? = 41L

    override suspend fun findByIdentity(identityKey: String): RemoteConnectionEntity? = null

    override suspend fun create(config: RemoteRepositoryConfig): CreatedRemoteRepository =
        CreatedRemoteRepository(repositoryId = 31L, connectionId = 41L)

    override suspend fun updateStatus(connectionId: Long, status: ConnectionStatus) {
        statuses[connectionId] = status
    }

    override suspend fun getConnection(connectionId: Long): RemoteConnectionEntity? =
        smbConnections.firstOrNull { it.id == connectionId }

    override suspend fun getSmbConnections(): List<RemoteConnectionEntity> = smbConnections

    override suspend fun remove(repositoryId: Long): Long? {
        removedRepositories += repositoryId
        return removeConnectionId
    }
}

private class FakeCredentialStore(
    private val failStore: Boolean = false,
    private val failDelete: Boolean = false,
) : CredentialStore {
    val values = mutableMapOf<Long, CharArray>()

    override fun storeCredential(connectionId: Long, credential: CharArray) {
        if (failStore) throw SecurityException("fixture")
        values[connectionId] = credential.copyOf()
    }

    override fun getCredential(connectionId: Long): CharArray? = values[connectionId]?.copyOf()

    override fun deleteCredential(connectionId: Long) {
        if (failDelete) throw SecurityException("fixture")
        values.remove(connectionId)?.fill('\u0000')
    }

    override fun hasCredential(connectionId: Long): Boolean = values.containsKey(connectionId)
}

private class FakeSessionInvalidator : RemoteSessionInvalidator {
    val invalidated = mutableListOf<Long>()
    override fun invalidate(connectionId: Long) {
        invalidated += connectionId
    }
}

private class FakeExternalCleaner : RemoteRepositoryExternalCleaner {
    val prepared = mutableListOf<Long>()
    val completed = mutableListOf<Long>()

    override suspend fun prepare(repositoryId: Long) {
        prepared += repositoryId
    }

    override suspend fun complete(repositoryId: Long) {
        completed += repositoryId
    }
}
