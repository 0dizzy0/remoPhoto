package com.remophoto.data.remote.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
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
import java.io.InputStream
import java.util.Collections
import java.util.EnumSet
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

data class SmbDirectoryEntry(
    val name: String,
    val isDirectory: Boolean,
    val isReparsePoint: Boolean,
    val size: Long,
    val lastModified: Long,
)

interface SmbFileHandle : Closeable {
    val inputStream: InputStream
}

/** 第三方 SMBJ 对象只存在于此边界内部。 */
interface SmbShareSession : Closeable {
    val dialect: String
    val signingRequired: Boolean
    fun list(path: String): List<SmbDirectoryEntry>
    fun openReadOnly(path: String): SmbFileHandle
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
    private val activeReads = ConcurrentHashMap<Long, MutableSet<SmbManagedRead>>()

    init {
        require(maxConcurrentOperations > 0) { "SMB 并发限制必须大于 0" }
        require(operationTimeoutMs > 0) { "SMB 超时必须大于 0" }
    }

    suspend fun testConnection(connection: RemoteConnectionEntity): SmbConnectionReport {
        require(connection.type == RemoteType.SMB) { "连接类型不是 SMB" }
        val startedAt = System.currentTimeMillis()
        return execute(connection) { session ->
            val path = SmbPathCodec.serverPath(connection.rootPath, relativePath = "")
            val count = runInterruptible(Dispatchers.IO) { session.list(path).size }
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

    /**
     * 使用尚未落盘的临时凭据测试 SMB 配置。该方法会在返回前清零 [credential]，
     * 用于“测试通过后才保存”的添加与重新认证流程。
     */
    suspend fun testConnection(
        connection: RemoteConnectionEntity,
        credential: CharArray,
    ): SmbConnectionReport {
        require(connection.type == RemoteType.SMB) { "连接类型不是 SMB" }
        require(credential.isNotEmpty()) { "SMB 密码不能为空" }
        val startedAt = System.currentTimeMillis()
        return executeWithCredential(connection, credential, "连接测试") { session ->
            val path = SmbPathCodec.serverPath(connection.rootPath, relativePath = "")
            val count = runInterruptible(Dispatchers.IO) { session.list(path).size }
            SmbConnectionReport(
                entryCount = count,
                dialect = session.dialect,
                signingRequired = session.signingRequired,
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
        }.also { report ->
            AppLogger.i(
                TAG,
                "SMB 临时配置测试完成: dialect=${report.dialect}, entries=${report.entryCount}, " +
                    "elapsedMs=${report.elapsedMs}",
            )
        }
    }

    /**
     * 使用尚未保存的配置列出指定目录的直属子目录，供添加仓库时选择相册根目录。
     * 密码仅在本次调用中使用，并在返回前清零。
     */
    suspend fun listDirectories(
        connection: RemoteConnectionEntity,
        credential: CharArray,
        relativePath: String,
    ): List<SmbDirectoryEntry> {
        require(connection.type == RemoteType.SMB) { "连接类型不是 SMB" }
        require(credential.isNotEmpty()) { "SMB 密码不能为空" }
        return executeWithCredential(connection, credential, "目录浏览") { session ->
            val serverPath = SmbPathCodec.serverPath(connection.rootPath, relativePath)
            listChildDirectories(session, serverPath)
        }
    }

    /** 使用已安全保存的凭据浏览现有 SMB 仓库。 */
    suspend fun listDirectories(
        connection: RemoteConnectionEntity,
        relativePath: String,
    ): List<SmbDirectoryEntry> = execute(connection.copy(rootPath = null)) { session ->
        val serverPath = SmbPathCodec.serverPath(rootPath = null, relativePath = relativePath)
        listChildDirectories(session, serverPath)
    }

    private suspend fun listChildDirectories(
        session: SmbShareSession,
        serverPath: String,
    ): List<SmbDirectoryEntry> = runInterruptible(Dispatchers.IO) { session.list(serverPath) }
        .asSequence()
        .filter { it.isDirectory && !it.isReparsePoint && it.name != "." && it.name != ".." }
        .sortedBy { it.name.lowercase() }
        .toList()

    /**
     * 长目录扫描只限制建连时长；SMBJ 自身对每次 list 请求设置 socket 超时。
     * 不能对整个扫描设置固定 30 秒上限，否则大型图库必然在多轮重试后失败。
     */
    internal suspend fun <T> executeCatalogScan(
        connection: RemoteConnectionEntity,
        block: suspend (SmbShareSession) -> T,
    ): T = executeInternal(connection, timeoutWholeBlock = false, block = block)

    /**
     * 将只读文件流的生命周期转交给调用方。调用方必须 close；close 会释放 file、session 和并发许可。
     */
    suspend fun openReadOnly(
        connection: RemoteConnectionEntity,
        serverPath: String,
    ): SmbManagedRead {
        require(connection.type == RemoteType.SMB) { "连接类型不是 SMB" }
        permits.acquire()
        val credential = credentialStore.getCredential(connection.id)
        if (credential == null) {
            permits.release()
            throw RemoteDataException(RemoteErrorCategory.AUTH_FAILED, "SMB 凭据缺失")
        }
        var session: SmbShareSession? = null
        var file: SmbFileHandle? = null
        var transferred = false
        try {
            withTimeout(operationTimeoutMs) {
                runInterruptible(Dispatchers.IO) {
                    session = backend.open(connection, credential).also { opened ->
                        register(connection.id, opened)
                    }
                    file = checkNotNull(session).openReadOnly(serverPath)
                }
            }
            val managed = SmbManagedRead(checkNotNull(file)) {
                val opened = checkNotNull(session)
                unregisterRead(connection.id, it)
                unregister(connection.id, opened)
                runCatching { opened.close() }
                permits.release()
                AppLogger.d(TAG, "SMB 读取资源已关闭: connectionId=${connection.id}, active=${activeCount()}")
            }
            registerRead(connection.id, managed)
            transferred = true
            return managed
        } catch (timeout: TimeoutCancellationException) {
            AppLogger.e(TAG, "SMB 文件打开超时: connectionId=${connection.id}, category=TIMEOUT")
            throw RemoteDataException(RemoteErrorCategory.TIMEOUT, "SMB 文件打开超时", timeout)
        } catch (cancelled: CancellationException) {
            AppLogger.i(TAG, "SMB 文件打开取消: connectionId=${connection.id}, category=CANCELLED")
            throw cancelled
        } catch (error: RemoteDataException) {
            throw error
        } catch (error: Throwable) {
            val mapped = SmbErrorMapper.exception(error)
            AppLogger.e(TAG, "SMB 文件打开失败: connectionId=${connection.id}, category=${mapped.category}")
            throw mapped
        } finally {
            credential.fill('\u0000')
            if (!transferred) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { file?.close() } }
                session?.let { opened ->
                    unregister(connection.id, opened)
                    withContext(NonCancellable + Dispatchers.IO) { runCatching { opened.close() } }
                }
                permits.release()
            }
        }
    }

    internal suspend fun <T> execute(
        connection: RemoteConnectionEntity,
        block: suspend (SmbShareSession) -> T,
    ): T = executeInternal(connection, timeoutWholeBlock = true, block = block)

    private suspend fun <T> executeInternal(
        connection: RemoteConnectionEntity,
        timeoutWholeBlock: Boolean,
        block: suspend (SmbShareSession) -> T,
    ): T = permits.withPermit {
        val credential = credentialStore.getCredential(connection.id)
            ?: throw RemoteDataException(RemoteErrorCategory.AUTH_FAILED, "SMB 凭据缺失")
        var session: SmbShareSession? = null
        try {
            suspend fun openSession() = withTimeout(operationTimeoutMs) {
                runInterruptible(Dispatchers.IO) {
                    // 在可取消边界内部先登记，避免 open 已成功但结果因取消被丢弃而泄漏。
                    backend.open(connection, credential).also { opened ->
                        session = opened
                        register(connection.id, opened)
                    }
                }
            }
            openSession()
            if (timeoutWholeBlock) withTimeout(operationTimeoutMs) { block(checkNotNull(session)) }
            else block(checkNotNull(session))
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

    private suspend fun <T> executeWithCredential(
        connection: RemoteConnectionEntity,
        credential: CharArray,
        operation: String,
        block: suspend (SmbShareSession) -> T,
    ): T {
        var session: SmbShareSession? = null
        var permitAcquired = false
        return try {
            permits.acquire()
            permitAcquired = true
            withTimeout(operationTimeoutMs) {
                runInterruptible(Dispatchers.IO) {
                    backend.open(connection, credential).also { opened ->
                        session = opened
                        register(connection.id, opened)
                    }
                }
                block(checkNotNull(session))
            }
        } catch (timeout: TimeoutCancellationException) {
            AppLogger.e(TAG, "SMB $operation 超时: category=TIMEOUT")
            throw RemoteDataException(RemoteErrorCategory.TIMEOUT, "SMB $operation 超时", timeout)
        } catch (cancelled: CancellationException) {
            AppLogger.i(TAG, "SMB $operation 取消: category=CANCELLED")
            throw cancelled
        } catch (error: RemoteDataException) {
            throw error
        } catch (error: Throwable) {
            val mapped = SmbErrorMapper.exception(error)
            AppLogger.e(TAG, "SMB $operation 失败: category=${mapped.category}, cause=${safeCause(error)}")
            throw mapped
        } finally {
            credential.fill('\u0000')
            session?.let { opened ->
                unregister(connection.id, opened)
                withContext(NonCancellable + Dispatchers.IO) { runCatching { opened.close() } }
            }
            if (permitAcquired) permits.release()
        }
    }

    private fun safeCause(error: Throwable): String = generateSequence(error) { it.cause }
        .take(4)
        .joinToString(">") { it.javaClass.simpleName.ifBlank { "Throwable" } }

    override fun invalidate(connectionId: Long) {
        activeReads.remove(connectionId)?.toList().orEmpty().forEach { runCatching { it.close() } }
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

    private fun registerRead(connectionId: Long, read: SmbManagedRead) {
        activeReads.computeIfAbsent(connectionId) {
            Collections.synchronizedSet(mutableSetOf())
        }.add(read)
    }

    private fun unregisterRead(connectionId: Long, read: SmbManagedRead) {
        activeReads[connectionId]?.let { reads ->
            reads.remove(read)
            if (reads.isEmpty()) activeReads.remove(connectionId, reads)
        }
    }

    private companion object {
        const val TAG = "SmbSessionManager"
    }
}

class SmbManagedRead internal constructor(
    private val file: SmbFileHandle,
    private val onClosed: (SmbManagedRead) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    val inputStream: InputStream get() = file.inputStream

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { file.close() }
        onClosed(this)
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

    override fun list(path: String): List<SmbDirectoryEntry> = share.list(path).map { entry ->
        val attributes = entry.fileAttributes
        SmbDirectoryEntry(
            name = entry.fileName,
            isDirectory = attributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
            isReparsePoint = attributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L,
            size = entry.endOfFile.coerceAtLeast(0L),
            lastModified = entry.lastWriteTime.toEpochMillis().coerceAtLeast(0L),
        )
    }

    override fun openReadOnly(path: String): SmbFileHandle {
        val remoteFile = share.openFile(
            path,
            SmbReadOnlyAccess.fileAccess,
            EnumSet.noneOf(FileAttributes::class.java),
            SmbReadOnlyAccess.shareAccess,
            SMB2CreateDisposition.FILE_OPEN,
            setOf(
                SMB2CreateOptions.FILE_NON_DIRECTORY_FILE,
                SMB2CreateOptions.FILE_SEQUENTIAL_ONLY,
            ),
        )
        return try {
            val stream = remoteFile.inputStream
            object : SmbFileHandle {
                private val handleClosed = AtomicBoolean(false)
                override val inputStream: InputStream = stream

                override fun close() {
                    if (!handleClosed.compareAndSet(false, true)) return
                    runCatching { stream.close() }
                    runCatching { remoteFile.close() }
                }
            }
        } catch (error: Throwable) {
            runCatching { remoteFile.close() }
            throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}
