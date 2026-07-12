package com.remophoto.data.remote

import com.remophoto.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程 HTTP 客户端
 *
 * 封装 HttpURLConnection，提供简洁的 suspend API 用于与 remoPhoto 远程设备通信。
 * 内置超时、错误处理和 JSON 解析。
 *
 * 用法：
 * ```
 * val client = RemoteHttpClient()
 * val albums = client.getAlbums("192.168.1.5", 8080)
 * val images = client.getImages("192.168.1.5", 8080, albumId = 1, page = 1)
 * val stream = client.getImageStream("192.168.1.5", 8080, imageId = 42)
 * ```
 */
interface RemoteHttpApi {
    suspend fun ping(host: String, port: Int): Boolean

    suspend fun getAlbums(host: String, port: Int): List<RemoteAlbumDto>

    suspend fun getImages(
        host: String,
        port: Int,
        albumId: Long,
        page: Int = 1,
        pageSize: Int = 50,
    ): RemoteImageListResponse
}

class RemoteHttpClient : RemoteHttpApi {

    companion object {
        private const val TAG = "RemoteHttpClient"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    /**
     * 测试连接可达性
     */
    override suspend fun ping(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://$host:$port/api")
            AppLogger.d(TAG, "ping 开始: timeoutMs=$CONNECT_TIMEOUT_MS")
            val conn = openConnection(url)
            val code = conn.responseCode
            conn.disconnect()
            AppLogger.i(TAG, "ping 完成: httpCode=$code")
            code == 200
        } catch (e: java.net.ConnectException) {
            AppLogger.w(TAG, "ping 失败: category=ConnectException")
            false
        } catch (e: java.net.SocketTimeoutException) {
            AppLogger.w(TAG, "ping 失败: category=SocketTimeoutException")
            false
        } catch (e: java.net.UnknownHostException) {
            AppLogger.w(TAG, "ping 失败: category=UnknownHostException")
            false
        } catch (e: Exception) {
            AppLogger.e(TAG, "ping 失败: category=${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * 获取相册列表
     */
    override suspend fun getAlbums(host: String, port: Int): List<RemoteAlbumDto> = withContext(Dispatchers.IO) {
        try {
            val json = getJson("http://$host:$port/api/albums")
            val arr = json.getJSONArray("albums")
            (0 until arr.length()).map { RemoteAlbumDto.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取相册列表失败: category=${e.javaClass.simpleName}")
            throw e
        }
    }

    /**
     * 获取相册图片列表（分页）
     */
    override suspend fun getImages(
        host: String, port: Int, albumId: Long, page: Int, pageSize: Int
    ): RemoteImageListResponse = withContext(Dispatchers.IO) {
        try {
            val json = getJson("http://$host:$port/api/album/$albumId/images?page=$page&pageSize=$pageSize")
            val arr = json.getJSONArray("images")
            val images = (0 until arr.length()).map { RemoteImageDto.fromJson(arr.getJSONObject(it)) }
            RemoteImageListResponse(
                images = images,
                totalCount = json.getInt("totalCount"),
                page = json.getInt("page"),
                pageSize = json.getInt("pageSize")
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取图片列表失败: category=${e.javaClass.simpleName}")
            throw e
        }
    }

    /**
     * 获取图片元数据
     */
    suspend fun getImageInfo(host: String, port: Int, imageId: Long): RemoteImageDto =
        withContext(Dispatchers.IO) {
            try {
                val json = getJson("http://$host:$port/api/image/$imageId/info")
                RemoteImageDto.fromJson(json)
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取图片信息失败: category=${e.javaClass.simpleName}")
                throw e
            }
        }

    /**
     * 获取图片文件流
     */
    suspend fun getImageStream(host: String, port: Int, imageId: Long): InputStream? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$host:$port/api/image/$imageId")
                val conn = openConnection(url)
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@withContext null
                }
                BufferedInputStream(conn.inputStream)
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取图片流失败: category=${e.javaClass.simpleName}")
                null
            }
        }

    /**
     * 获取缩略图文件流
     */
    suspend fun getThumbnailStream(host: String, port: Int, imageId: Long): InputStream? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$host:$port/api/image/$imageId/thumb")
                val conn = openConnection(url)
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@withContext null
                }
                BufferedInputStream(conn.inputStream)
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取缩略图流失败: category=${e.javaClass.simpleName}")
                null
            }
        }

    // ===== Private =====

    private suspend fun getJson(urlString: String): JSONObject = withContext(Dispatchers.IO) {
        val url = URL(urlString)
        val conn = openConnection(url)
        try {
            if (conn.responseCode != 200) {
                throw RuntimeException("HTTP ${conn.responseCode}")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(url: URL): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            requestMethod = "GET"
        }
    }
}

/**
 * 远程相册 DTO（API 响应反序列化）
 */
data class RemoteAlbumDto(
    val id: Long,
    val name: String,
    val imageCount: Int,
    val repositoryId: Long,
    val parentAlbumId: Long? = null,
    val coverImageId: Long? = null,
    val lastModified: Long = 0L
) {
    companion object {
        fun fromJson(json: JSONObject): RemoteAlbumDto = RemoteAlbumDto(
            id = json.getLong("id"),
            name = json.getString("name"),
            imageCount = json.getInt("imageCount"),
            repositoryId = json.getLong("repositoryId"),
            parentAlbumId = if (json.isNull("parentAlbumId")) null else json.getLong("parentAlbumId"),
            // 可选字段保持新客户端连接旧服务端时的兼容性。
            coverImageId = if (!json.has("coverImageId") || json.isNull("coverImageId")) {
                null
            } else {
                json.getLong("coverImageId")
            },
            // 旧服务端没有此字段时保持兼容。
            lastModified = json.optLong("lastModified", 0L)
        )
    }
}

/**
 * 远程图片 DTO（API 响应反序列化）
 */
data class RemoteImageDto(
    val id: Long,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val albumId: Long,
    val repositoryId: Long
) {
    companion object {
        fun fromJson(json: JSONObject): RemoteImageDto = RemoteImageDto(
            id = json.getLong("id"),
            fileName = json.getString("fileName"),
            fileSize = json.getLong("fileSize"),
            lastModified = json.getLong("lastModified"),
            width = json.getInt("width"),
            height = json.getInt("height"),
            mimeType = json.getString("mimeType"),
            albumId = json.getLong("albumId"),
            repositoryId = json.getLong("repositoryId")
        )
    }
}

/**
 * 远程图片列表响应（分页）
 */
data class RemoteImageListResponse(
    val images: List<RemoteImageDto>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int
)
