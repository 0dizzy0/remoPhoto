package com.remophoto.data.remote.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.remote.RemoteDataException
import com.remophoto.data.remote.RemoteErrorCategory
import com.remophoto.data.repository.RemoteSessionInvalidator
import com.remophoto.data.security.CredentialStore
import com.remophoto.util.AppLogger
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class SmbConnectionReport(
    val entryCount: Int,
    val dialect: String,
    val signingRequired: Boolean,
    val elapsedMs: Long,
)

/** 第三方 SMBJ 对象只存在于此边界内部。 */
interface SmbShareSession : Closeable {
    val dialect: String
    val signingRequired: Boolean
    fun list(path: String): Int
}

fun interface SmbSessionBackend {
    fun open(connection: RemoteConnectionEntity, credential: CharArray): SmbShareSession
}

/** M3 打开文件时必须复用这些只读权限，禁止 MAXIMUM_ALLOWED/GENERIC_WRITE。 */
object SmbReadOnlyAccess {
    val fileAccess: Set<AccessMask> = Collections.unmodifiableSet(
        setOf(
            AccessMask.FILE_READ_DATA,
            AccessMask.FILE_READ_ATTRIBUTES,
            AccessMask.FILE_READ_EA,
            AccessMask.READ_CONTROL,
            AccessMask.SYNCHRONIZE,
        )
    )
    val shareAccess: Set<SMB2ShareAccess> = Collections.singleton(SMB2ShareAccess.FILE_SHARE_READ)
}

class SmbSessionManager(
    private val credentialStore: CredentialStore,
    private val backend: SmbSessionBackend = SmbjSessionBackend(),
    maxConcurrentOperations: Int = 3,
    private val operationTimeoutMs: Long = 30_000L,
) : RemoteSessionInvalidator, Closeable {
    private val permits = Semaphore(maxConcurrentOperations)
    private val active = ConcurrentHashMap<Long, MutableSet<SmbShareSession>>()

    init {
        require(maxConcurrentOperations > 0) { "SMB 并发限制必须大于 0" }
        require(operationTimeoutMs > 0) { "SMB 超时必须大于 0" }
    }

    suspend fun testConnection(connection: RemoteConnectionEntity): SmbConnectionReport {
        require(connection.type == RemoteType.SMB) { "连接类型不是 SMB" }
        val startedAt = System.currentTimeMillis()
        return execute(connection) { session ->
            val path = connection.rootPath.orEmpty().replace('/', '\\')
            val count = runInterruptible(Dispatchers.IO) { session.list(path) }
            SmbConnectionReport(
                entryCount = count,
                dialect = session.dialect,
                signingRequired = session.signingRequired,
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
        }.also { report ->
            AppLogger.i(
                TAG,
                "SMB 连接测试完成: connectionId=${connection.id}, dialect=${report.dialect}, " +
                    "entries=${report.entryCount}, elapsedMs=${report.elapsedMs}",
            )
        }
    }

    internal suspend fun <T> execute(
        connection: RemoteConnectionEntity,
        block: suspend (SmbShareSession) -> T,
    ): T = permits.withPermit {
        val credential = credentialStore.getCredential(connection.id)
            ?: throw RemoteDataException(RemoteErrorCategory.AUTH_FAILED, "SMB 凭据缺失")
        var session: SmbShareSession? = null
        try {
            withTimeout(operationTimeoutMs) {
                runInterruptible(Dispatchers.IO) {
                    // 在可取消边界内部先登记，避免 open 已成功但结果因取消被丢弃而泄漏。
                    backend.open(connection, credential).also { opened ->
                        session = opened
                        register(connection.id, opened)
                    }
                }
                block(checkNotNull(session))
            }
        } catch (timeout: TimeoutCancellationException) {
            AppLogger.e(TAG, "SMB 操作超时: connectionId=${connection.id}, category=TIMEOUT")
            throw RemoteDataException(RemoteErrorCategory.TIMEOUT, "SMB 操作超时", timeout)
        } catch (cancelled: CancellationException) {
            AppLogger.i(TAG, "SMB 操作取消: connectionId=${connection.id}, category=CANCELLED")
            throw cancelled
        } catch (error: RemoteDataException) {
            throw error
        } catch (error: Throwable) {
            val mapped = SmbErrorMapper.exception(error)
            AppLogger.e(TAG, "SMB 操作失败: connectionId=${connection.id}, category=${mapped.category}")
            throw mapped
        } finally {
            credential.fill('\u0000')
            session?.let { opened ->
                unregister(connection.id, opened)
                withContext(NonCancellable + Dispatchers.IO) { runCatching { opened.close() } }
                AppLogger.d(TAG, "SMB 资源已关闭: connectionId=${connection.id}, active=${activeCount()}")
            }
        }
    }

    override fun invalidate(connectionId: Long) {
        val sessions = active.remove(connectionId)?.toList().orEmpty()
        sessions.forEach { runCatching { it.close() } }
        if (sessions.isNotEmpty()) {
            AppLogger.i(TAG, "SMB 会话已失效: connectionId=$connectionId, closed=${sessions.size}")
        }
    }

    override fun close() {
        active.keys.toList().forEach(::invalidate)
    }

    internal fun activeCount(): Int = active.values.sumOf { set -> synchronized(set) { set.size } }

    private fun register(connectionId: Long, session: SmbShareSession) {
        val sessions = active.computeIfAbsent(connectionId) {
            Collections.synchronizedSet(mutableSetOf())
        }
        sessions.add(session)
    }

    private fun unregister(connectionId: Long, session: SmbShareSession) {
        active[connectionId]?.let { sessions ->
            sessions.remove(session)
            if (sessions.isEmpty()) active.remove(connectionId, sessions)
        }
    }

    private companion object {
        const val TAG = "SmbSessionManager"
    }
}

class SmbjSessionBackend : SmbSessionBackend {
    private val config: SmbConfig = SmbConfig.builder()
        .withDialects(
            SMB2Dialect.SMB_2_0_2,
            SMB2Dialect.SMB_2_1,
            SMB2Dialect.SMB_3_0,
            SMB2Dialect.SMB_3_0_2,
            SMB2Dialect.SMB_3_1_1,
        )
        .withDfsEnabled(false)
        .withTimeout(30, TimeUnit.SECONDS)
        .withSoTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun open(connection: RemoteConnectionEntity, credential: CharArray): SmbShareSession {
        var client: SMBClient? = null
        var transport: Connection? = null
        var session: Session? = null
        var share: DiskShare? = null
        try {
            client = SMBClient(config)
            transport = client.connect(connection.host, connection.port)
            val authentication = AuthenticationContext(
                checkNotNull(connection.username),
                credential,
                connection.domain,
            )
            session = transport.authenticate(authentication)
            if (session.isGuest || session.isAnonymous) {
                throw RemoteDataException(RemoteErrorCategory.AUTH_FAILED, "不支持 Guest 或匿名 SMB")
            }
            share = session.connectShare(checkNotNull(connection.shareName)) as? DiskShare
                ?: throw RemoteDataException(RemoteErrorCategory.SHARE_NOT_FOUND, "目标不是磁盘共享")
            val dialect = transport.negotiatedProtocol.dialect
            if (dialect == SMB2Dialect.UNKNOWN) {
                throw RemoteDataException(RemoteErrorCategory.UNSUPPORTED_DIALECT, "未协商到 SMB2/3")
            }
            return SmbjShareSession(client, transport, session, share, dialect.name)
        } catch (error: Throwable) {
            runCatching { share?.close() }
            runCatching { session?.close() }
            runCatching { transport?.close() }
            runCatching { client?.close() }
            throw error
        }
    }
}

private class SmbjShareSession(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
    override val dialect: String,
) : SmbShareSession {
    private val closed = AtomicBoolean(false)
    override val signingRequired: Boolean = session.isSigningRequired

    override fun list(path: String): Int = share.list(path).size

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}
