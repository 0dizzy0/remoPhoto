package com.remophoto.data.remote.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.remophoto.data.remote.RemoteDataException
import com.remophoto.data.remote.RemoteErrorCategory
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

object SmbErrorMapper {
    fun category(error: Throwable): RemoteErrorCategory {
        val chain = generateSequence(error as Throwable?) { it.cause }
        val apiError = chain.filterIsInstance<SMBApiException>().firstOrNull()
        if (apiError != null) return category(apiError.status)
        return when {
            chain.any { it is SocketTimeoutException || it is TimeoutException } ->
                RemoteErrorCategory.TIMEOUT
            chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } ->
                RemoteErrorCategory.HOST_UNREACHABLE
            chain.any { it is TransportException } -> RemoteErrorCategory.HOST_UNREACHABLE
            else -> RemoteErrorCategory.UNKNOWN
        }
    }

    fun category(status: NtStatus): RemoteErrorCategory = when (status) {
        NtStatus.STATUS_LOGON_FAILURE,
        NtStatus.STATUS_PASSWORD_EXPIRED,
        NtStatus.STATUS_ACCOUNT_DISABLED,
        NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
        -> RemoteErrorCategory.AUTH_FAILED

        NtStatus.STATUS_BAD_NETWORK_NAME -> RemoteErrorCategory.SHARE_NOT_FOUND

        NtStatus.STATUS_ACCESS_DENIED,
        NtStatus.STATUS_PRIVILEGE_NOT_HELD,
        -> RemoteErrorCategory.ACCESS_DENIED

        NtStatus.STATUS_TIMEOUT,
        NtStatus.STATUS_IO_TIMEOUT,
        -> RemoteErrorCategory.TIMEOUT

        NtStatus.STATUS_OBJECT_NAME_INVALID,
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
        NtStatus.STATUS_NAME_TOO_LONG,
        -> RemoteErrorCategory.PATH_INVALID

        NtStatus.STATUS_INSUFFICIENT_RESOURCES,
        NtStatus.STATUS_INSUFF_SERVER_RESOURCES,
        NtStatus.STATUS_TOO_MANY_OPENED_FILES,
        -> RemoteErrorCategory.RESOURCE_LIMIT

        NtStatus.STATUS_CANCELLED -> RemoteErrorCategory.CANCELLED

        NtStatus.STATUS_BAD_NETWORK_PATH,
        NtStatus.STATUS_NETWORK_NAME_DELETED,
        NtStatus.STATUS_CONNECTION_DISCONNECTED,
        NtStatus.STATUS_CONNECTION_RESET,
        -> RemoteErrorCategory.HOST_UNREACHABLE

        else -> RemoteErrorCategory.UNKNOWN
    }

    fun exception(error: Throwable): RemoteDataException {
        val category = category(error)
        return RemoteDataException(
            category = category,
            message = "SMB 操作失败: category=$category",
            cause = error,
        )
    }
}
