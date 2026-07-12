package com.remophoto.data.remote.smb

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.remophoto.RemoPhotoApp
import com.remophoto.data.remote.RemoteMediaRef
import com.remophoto.util.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import okio.ForwardingSource
import okio.Source
import okio.buffer
import okio.source

class SmbImageFetcher(
    private val ref: RemoteMediaRef.Smb,
    private val options: Options,
    private val resolver: SmbMediaResolver,
    private val sessionManager: SmbSessionManager,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val startedAt = System.currentTimeMillis()
        val resolved = resolver.resolve(ref)
        val read = sessionManager.openReadOnly(resolved.connection, resolved.serverPath)
        return try {
            val source = ManagedReadSource(read.inputStream.source(), read).buffer()
            SourceResult(
                source = ImageSource(source = source, context = options.context),
                mimeType = resolved.mimeType,
                dataSource = DataSource.NETWORK,
            ).also {
                AppLogger.i(
                    TAG,
                    "SMB 媒体流已打开: connectionId=${ref.connectionId}, variant=${ref.variant}, " +
                        "elapsedMs=${System.currentTimeMillis() - startedAt}",
                )
            }
        } catch (error: Throwable) {
            read.close()
            throw error
        }
    }

    class Factory(
        private val injectedResolver: SmbMediaResolver? = null,
        private val injectedSessionManager: SmbSessionManager? = null,
    ) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val ref = RemoteMediaRef.Smb.parse(data.toString()) ?: return null
            val container = if (injectedResolver == null || injectedSessionManager == null) {
                (options.context.applicationContext as RemoPhotoApp).dependencyContainer
            } else {
                null
            }
            return SmbImageFetcher(
                ref = ref,
                options = options,
                resolver = injectedResolver ?: checkNotNull(container).smbMediaResolver,
                sessionManager = injectedSessionManager ?: checkNotNull(container).smbSessionManager,
            )
        }
    }

    private class ManagedReadSource(
        delegate: Source,
        private val managedRead: SmbManagedRead,
    ) : ForwardingSource(delegate) {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                super.close()
            } finally {
                managedRead.close()
            }
        }
    }

    private companion object {
        const val TAG = "SmbImageFetcher"
    }
}
