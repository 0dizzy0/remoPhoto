package com.remophoto.data.remote

import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.remophoto.util.AppLogger
import okio.Buffer
import okio.buffer
import okio.source
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Coil Remote Image Fetcher
 *
 * 自定义 Coil Fetcher，支持从 remoPhoto HTTP 服务器加载远程图片。
 * 与 Coil 磁盘缓存集成——首次加载后自动缓存，离线时从缓存读取。
 *
 * 注册到 Coil ImageLoader：
 * ```
 * ImageLoader.Builder(context)
 *   .components { add(RemoteImageFetcher.Factory()) }
 *   .build()
 * ```
 */
class RemoteImageFetcher(
    private val url: String,
    private val options: Options
) : Fetcher {

    companion object {
        const val TAG = "RemoteImageFetcher"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    override suspend fun fetch(): FetchResult {
        AppLogger.d(TAG, "远程图片读取开始")

        val connection = openConnection(url)
        return try {
            if (connection.responseCode != 200) {
                throw RuntimeException("远程图片 HTTP ${connection.responseCode}")
            }

            val inputStream = BufferedInputStream(connection.inputStream)
            val buffer = Buffer()
            inputStream.source().buffer().use { source ->
                buffer.writeAll(source)
            }

            val mimeType = connection.contentType ?: "image/jpeg"
            val imageSource = ImageSource(
                source = buffer,
                context = options.context
            )
            SourceResult(
                source = imageSource,
                mimeType = mimeType,
                dataSource = DataSource.NETWORK
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(urlStr: String): HttpURLConnection {
        val url = URL(urlStr)
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = true
            requestMethod = "GET"
        }
    }

    /**
     * Coil Fetcher Factory — 判断 URL 是否由此 Fetcher 处理
     */
    class Factory : Fetcher.Factory<String> {
        override fun create(
            data: String,
            options: Options,
            imageLoader: coil.ImageLoader
        ): Fetcher? {
            // 处理 HTTP 远程图片 URL（含 remoPhoto API 路径）
            if (data.startsWith("http://") || data.startsWith("https://")) {
                return RemoteImageFetcher(data, options)
            }
            return null
        }
    }
}
