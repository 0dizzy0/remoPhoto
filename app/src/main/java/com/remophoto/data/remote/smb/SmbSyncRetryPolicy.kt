package com.remophoto.data.remote.smb

import com.remophoto.data.remote.RemoteDataException
import com.remophoto.data.remote.RemoteErrorCategory

object SmbSyncRetryPolicy {
    const val MAX_ATTEMPTS = 3

    fun shouldRetry(error: Throwable, runAttemptCount: Int): Boolean {
        if (runAttemptCount >= MAX_ATTEMPTS - 1) return false
        val category = (error as? RemoteDataException)?.category ?: RemoteErrorCategory.UNKNOWN
        return category in setOf(
            RemoteErrorCategory.HOST_UNREACHABLE,
            RemoteErrorCategory.TIMEOUT,
            RemoteErrorCategory.UNKNOWN,
        )
    }
}
