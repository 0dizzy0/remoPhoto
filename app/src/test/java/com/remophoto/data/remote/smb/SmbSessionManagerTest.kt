package com.remophoto.data.remote.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.remote.RemoteDataException
import com.remophoto.data.remote.RemoteErrorCategory
import com.remophoto.data.security.CredentialStore
import com.remophoto.util.AppLogger
import java.net.SocketTimeoutException
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmbSessionManagerTest {
    @Before
    fun disableAndroidLogging() {
        AppLogger.logcatEnabled = false
        AppLogger.fileLogEnabled = false
    }

    @Test
    fun `closes every resource and clears credential after success`() = runBlocking {
        val credentials = RecordingCredentialStore()
        val share = FakeShareSession()
        val manager = SmbSessionManager(credentials, SmbSessionBackend { _, _ -> share })

        val report = manager.testConnection(connection())

        assertEquals(3, report.entryCount)
        assertTrue(share.closed.get())
        assertEquals(0, manager.activeCount())
        assertTrue(credentials.lastReturned!!.all { it == '\u0000' })
    }

    @Test
    fun `timeout is classified and closes active session`() = runBlocking {
        val share = FakeShareSession(listDelayMs = 10_000L)
        val manager = SmbSessionManager(
            RecordingCredentialStore(),
            SmbSessionBackend { _, _ -> share },
            operationTimeoutMs = 50L,
        )

        val error = runCatching { manager.testConnection(connection()) }.exceptionOrNull()

        assertTrue(error is RemoteDataException)
        assertEquals(RemoteErrorCategory.TIMEOUT, (error as RemoteDataException).category)
        assertTrue(share.closed.get())
        assertEquals(0, manager.activeCount())
    }

    @Test
    fun `caller cancellation interrupts work and closes session`() = runBlocking {
        val entered = CountDownLatch(1)
        val share = FakeShareSession(listDelayMs = 10_000L, entered = entered)
        val manager = SmbSessionManager(
            RecordingCredentialStore(),
            SmbSessionBackend { _, _ -> share },
        )
        val job = launch(Dispatchers.Default) { manager.testConnection(connection()) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(share.closed.get())
        assertEquals(0, manager.activeCount())
    }

    @Test
    fun `maps stable SMB error categories`() {
        assertEquals(RemoteErrorCategory.AUTH_FAILED, SmbErrorMapper.category(NtStatus.STATUS_LOGON_FAILURE))
        assertEquals(RemoteErrorCategory.SHARE_NOT_FOUND, SmbErrorMapper.category(NtStatus.STATUS_BAD_NETWORK_NAME))
        assertEquals(RemoteErrorCategory.ACCESS_DENIED, SmbErrorMapper.category(NtStatus.STATUS_ACCESS_DENIED))
        assertEquals(RemoteErrorCategory.TIMEOUT, SmbErrorMapper.category(SocketTimeoutException()))
        assertEquals(RemoteErrorCategory.RESOURCE_LIMIT, SmbErrorMapper.category(NtStatus.STATUS_TOO_MANY_OPENED_FILES))
    }

    @Test
    fun `read only access set excludes every write or delete permission`() {
        val denied = setOf(
            AccessMask.FILE_WRITE_DATA,
            AccessMask.FILE_APPEND_DATA,
            AccessMask.FILE_ADD_FILE,
            AccessMask.FILE_ADD_SUBDIRECTORY,
            AccessMask.FILE_DELETE_CHILD,
            AccessMask.FILE_WRITE_ATTRIBUTES,
            AccessMask.FILE_WRITE_EA,
            AccessMask.DELETE,
            AccessMask.GENERIC_WRITE,
            AccessMask.GENERIC_ALL,
            AccessMask.MAXIMUM_ALLOWED,
        )

        assertTrue(SmbReadOnlyAccess.fileAccess.intersect(denied).isEmpty())
        assertFalse(SmbReadOnlyAccess.fileAccess.isEmpty())
    }

    @Test
    fun `managed read holds and then releases session`() = runBlocking {
        val share = FakeShareSession()
        val manager = SmbSessionManager(
            RecordingCredentialStore(),
            SmbSessionBackend { _, _ -> share },
        )

        val read = manager.openReadOnly(connection(), "album\\image.jpg")
        assertEquals(1, manager.activeCount())
        assertEquals(1, read.inputStream.read())

        read.close()
        assertTrue(share.closed.get())
        assertEquals(0, manager.activeCount())
    }

    private fun connection() = RemoteConnectionEntity(
        id = 8L,
        type = RemoteType.SMB,
        host = "server",
        port = 445,
        displayName = "fixture",
        shareName = "share",
        username = "alice",
        addedTime = 1L,
    )
}

private class RecordingCredentialStore : CredentialStore {
    var lastReturned: CharArray? = null

    override fun storeCredential(connectionId: Long, credential: CharArray) = Unit

    override fun getCredential(connectionId: Long): CharArray = "secret".toCharArray().also {
        lastReturned = it
    }

    override fun deleteCredential(connectionId: Long) = Unit
    override fun hasCredential(connectionId: Long): Boolean = true
}

private class FakeShareSession(
    private val listDelayMs: Long = 0L,
    private val entered: CountDownLatch? = null,
) : SmbShareSession {
    val closed = AtomicBoolean(false)
    override val dialect: String = "SMB_3_1_1"
    override val signingRequired: Boolean = true

    override fun list(path: String): List<SmbDirectoryEntry> {
        entered?.countDown()
        if (listDelayMs > 0) Thread.sleep(listDelayMs)
        return List(3) { index ->
            SmbDirectoryEntry("entry-$index", false, false, 1L, 1L)
        }
    }

    override fun openReadOnly(path: String): SmbFileHandle = object : SmbFileHandle {
        override val inputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        override fun close() = inputStream.close()
    }

    override fun close() {
        closed.set(true)
    }
}
