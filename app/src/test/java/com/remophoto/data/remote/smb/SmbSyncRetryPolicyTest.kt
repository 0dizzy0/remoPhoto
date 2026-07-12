package com.remophoto.data.remote.smb

import com.remophoto.data.remote.RemoteDataException
import com.remophoto.data.remote.RemoteErrorCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbSyncRetryPolicyTest {
    @Test
    fun `retries only bounded transient failures`() {
        assertTrue(SmbSyncRetryPolicy.shouldRetry(remote(RemoteErrorCategory.TIMEOUT), 0))
        assertTrue(SmbSyncRetryPolicy.shouldRetry(remote(RemoteErrorCategory.HOST_UNREACHABLE), 1))
        assertFalse(SmbSyncRetryPolicy.shouldRetry(remote(RemoteErrorCategory.AUTH_FAILED), 0))
        assertFalse(SmbSyncRetryPolicy.shouldRetry(remote(RemoteErrorCategory.ACCESS_DENIED), 0))
        assertFalse(SmbSyncRetryPolicy.shouldRetry(remote(RemoteErrorCategory.TIMEOUT), 2))
    }

    private fun remote(category: RemoteErrorCategory) = RemoteDataException(category, "fixture")
}
