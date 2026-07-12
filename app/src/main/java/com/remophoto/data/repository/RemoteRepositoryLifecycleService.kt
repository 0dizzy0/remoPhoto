package com.remophoto.data.repository

import androidx.room.withTransaction
import com.remophoto.data.local.AppDatabase
import com.remophoto.data.local.dao.AlbumDao
import com.remophoto.data.local.dao.ImageDao
import com.remophoto.data.local.dao.RemoteConnectionDao
import com.remophoto.data.local.dao.RepositoryDao
import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.local.entity.RepositoryEntity
import com.remophoto.data.remote.RemoteConnectionIdentity
import com.remophoto.data.security.CredentialStore
import com.remophoto.util.AppLogger

data class RemoteRepositoryConfig(
    val type: RemoteType,
    val host: String,
    val port: Int,
    val displayName: String,
    val shareName: String? = null,
    val username: String? = null,
    val domain: String? = null,
    val rootPath: String? = null,
) {
    val identityKey: String = RemoteConnectionIdentity.create(
        type, host, port, shareName, rootPath, domain, username
    )
}

data class CreatedRemoteRepository(val repositoryId: Long, val connectionId: Long)
data class RemoveRemoteRepositoryResult(val removed: Boolean, val externalCleanupPending: Boolean)

interface RemoteSessionInvalidator {
    fun invalidate(connectionId: Long)
}

interface RemoteMetadataStore {
    suspend fun findByIdentity(identityKey: String): RemoteConnectionEntity?
    suspend fun create(config: RemoteRepositoryConfig): CreatedRemoteRepository
    suspend fun updateStatus(connectionId: Long, status: ConnectionStatus)
    suspend fun getConnection(connectionId: Long): RemoteConnectionEntity?
    suspend fun getSmbConnections(): List<RemoteConnectionEntity>
    suspend fun remove(repositoryId: Long): Long?
}

class RoomRemoteMetadataStore(
    private val database: AppDatabase,
    private val connectionDao: RemoteConnectionDao,
    private val repositoryDao: RepositoryDao,
    private val imageDao: ImageDao,
    private val albumDao: AlbumDao,
) : RemoteMetadataStore {
    override suspend fun findByIdentity(identityKey: String): RemoteConnectionEntity? =
        connectionDao.getConnectionByIdentity(identityKey)

    override suspend fun create(config: RemoteRepositoryConfig): CreatedRemoteRepository =
        database.withTransaction {
            val connectionId = connectionDao.insert(
                RemoteConnectionEntity(
                    type = config.type,
                    host = config.host.trim(),
                    port = config.port,
                    displayName = config.displayName.trim(),
                    shareName = config.shareName?.trim(),
                    username = config.username?.trim(),
                    domain = config.domain?.trim()?.ifBlank { null },
                    rootPath = config.rootPath?.trim()?.ifBlank { null },
                    identityKey = config.identityKey,
                    addedTime = System.currentTimeMillis(),
                    status = ConnectionStatus.DISCONNECTED,
                )
            )
            val uri = if (config.type == RemoteType.HTTP_MDNS) {
                "http://${config.host.trim()}:${config.port}"
            } else {
                "remote://connection/$connectionId"
            }
            val repositoryId = repositoryDao.insert(
                RepositoryEntity(
                    uriString = uri,
                    path = null,
                    name = config.displayName.trim(),
                    remoteConnectionId = connectionId,
                    addedTime = System.currentTimeMillis(),
                )
            )
            CreatedRemoteRepository(repositoryId, connectionId)
        }

    override suspend fun updateStatus(connectionId: Long, status: ConnectionStatus) {
        connectionDao.updateStatus(connectionId, status)
        if (status == ConnectionStatus.CONNECTED) {
            connectionDao.updateLastConnectedTime(connectionId, System.currentTimeMillis())
        }
    }

    override suspend fun getConnection(connectionId: Long): RemoteConnectionEntity? =
        connectionDao.getConnectionById(connectionId)

    override suspend fun getSmbConnections(): List<RemoteConnectionEntity> =
        connectionDao.getAllConnectionsList().filter { it.type == RemoteType.SMB }

    override suspend fun remove(repositoryId: Long): Long? = database.withTransaction {
        val repository = repositoryDao.getRepositoryById(repositoryId) ?: return@withTransaction null
        val connectionId = repository.remoteConnectionId
        imageDao.deleteByRepository(repositoryId)
        albumDao.deleteByRepository(repositoryId)
        repositoryDao.delete(repository)
        if (connectionId != null) connectionDao.deleteById(connectionId)
        connectionId
    }
}

class RemoteRepositoryLifecycleService(
    private val metadataStore: RemoteMetadataStore,
    private val credentialStore: CredentialStore,
    private val sessionInvalidator: RemoteSessionInvalidator,
) {
    suspend fun saveTested(
        config: RemoteRepositoryConfig,
        credential: CharArray? = null,
    ): CreatedRemoteRepository {
        require(config.displayName.isNotBlank()) { "仓库显示名不能为空" }
        if (config.type == RemoteType.SMB) {
            require(credential != null && credential.isNotEmpty()) { "SMB 密码不能为空" }
        }
        check(metadataStore.findByIdentity(config.identityKey) == null) { "该远程仓库已存在" }

        var created: CreatedRemoteRepository? = null
        try {
            created = metadataStore.create(config)
            if (config.type == RemoteType.SMB) {
                credentialStore.storeCredential(created.connectionId, checkNotNull(credential))
            }
            metadataStore.updateStatus(created.connectionId, ConnectionStatus.CONNECTED)
            AppLogger.i(
                TAG,
                "远程仓库保存完成: connectionId=${created.connectionId}, " +
                    "repositoryId=${created.repositoryId}, type=${config.type}",
            )
            return created
        } catch (error: Exception) {
            created?.let { partial ->
                runCatching { credentialStore.deleteCredential(partial.connectionId) }
                runCatching { metadataStore.remove(partial.repositoryId) }
                sessionInvalidator.invalidate(partial.connectionId)
            }
            AppLogger.e(TAG, "远程仓库保存回滚: category=${error.javaClass.simpleName}")
            throw error
        } finally {
            credential?.fill('\u0000')
        }
    }

    suspend fun reauthenticate(connectionId: Long, credential: CharArray) {
        val connection = checkNotNull(metadataStore.getConnection(connectionId)) { "远程连接不存在" }
        require(connection.type == RemoteType.SMB) { "只有 SMB 连接需要重新认证" }
        try {
            credentialStore.storeCredential(connectionId, credential)
            sessionInvalidator.invalidate(connectionId)
            metadataStore.updateStatus(connectionId, ConnectionStatus.CONNECTED)
            AppLogger.i(TAG, "SMB 凭据已更新: connectionId=$connectionId")
        } finally {
            credential.fill('\u0000')
        }
    }

    suspend fun recoverMissingCredentials(): Int {
        var recovered = 0
        metadataStore.getSmbConnections().forEach { connection ->
            if (!credentialStore.hasCredential(connection.id)) {
                sessionInvalidator.invalidate(connection.id)
                metadataStore.updateStatus(connection.id, ConnectionStatus.AUTH_REQUIRED)
                recovered++
            }
        }
        if (recovered > 0) AppLogger.w(TAG, "检测到缺失凭据: count=$recovered")
        return recovered
    }

    suspend fun remove(repositoryId: Long): RemoveRemoteRepositoryResult {
        val connectionId = metadataStore.remove(repositoryId)
            ?: return RemoveRemoteRepositoryResult(removed = false, externalCleanupPending = false)
        sessionInvalidator.invalidate(connectionId)
        val cleanupPending = runCatching { credentialStore.deleteCredential(connectionId) }.isFailure
        AppLogger.i(
            TAG,
            "远程仓库删除完成: repositoryId=$repositoryId, connectionId=$connectionId, " +
                "externalCleanupPending=$cleanupPending",
        )
        return RemoveRemoteRepositoryResult(removed = true, externalCleanupPending = cleanupPending)
    }

    private companion object {
        const val TAG = "RemoteRepoLifecycle"
    }
}
