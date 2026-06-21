package com.remophoto.data.server

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import com.remophoto.data.local.entity.ImageEntity
import com.remophoto.di.dependencies
import com.remophoto.util.AppLogger
import com.remophoto.util.Constants
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.ByteArrayOutputStream
import android.graphics.BitmapFactory
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * HTTP Server 状态
 */
enum class ServerStatus {
    STOPPED, STARTING, RUNNING, ERROR
}

/**
 * NanoHTTPd HTTP Server 管理器
 *
 * 将本设备相册通过 REST API 共享给局域网内其他 remoPhoto 设备浏览。
 * 仅监听局域网 IP（不绑定 0.0.0.0），确保安全。
 *
 * API 端点：
 * - GET /api/albums               → 根级相册列表 JSON
 * - GET /api/album/{id}/images    → 分页图片列表 JSON
 * - GET /api/image/{id}           → 原图文件流
 * - GET /api/image/{id}/thumb     → 缩略图（当前返回原图）
 * - GET /api/image/{id}/info      → 图片元数据 JSON
 */
class HttpServerManager(private val context: Context) {

    companion object {
        private const val TAG = "HttpServerManager"

        // API 路由正则（静态编译，避免重复创建）
        private val albumImagesRegex = Regex("/api/album/(\\d+)/images")
        private val imageThumbRegex = Regex("/api/image/(\\d+)/thumb")
        private val imageInfoRegex = Regex("/api/image/(\\d+)/info")
        private val imageStreamRegex = Regex("/api/image/(\\d+)$")
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val _status = MutableStateFlow(ServerStatus.STOPPED)
    val statusFlow: StateFlow<ServerStatus> = _status.asStateFlow()

    private var server: RemoPhotoServer? = null
    private var currentPort: Int = 0

    fun start(port: Int = Constants.REMOTE_HTTP_PORT, deviceName: String = ""): String? {
        return try {
            _status.value = ServerStatus.STARTING
            AppLogger.i(TAG, "正在启动 HTTP Server: port=$port")

            val lanIp = getLanIpAddress()
                ?: return reportError("无法获取局域网 IP，请确保已连接 WiFi")

            val serverInstance = RemoPhotoServer(lanIp, port, context, deviceName)
            serverInstance.start()
            server = serverInstance
            currentPort = port

            _status.value = ServerStatus.RUNNING
            val addr = "$lanIp:$port"
            AppLogger.i(TAG, "HTTP Server 已启动: http://$addr")
            addr
        } catch (e: Exception) {
            reportError("启动失败: ${e.message}")
        }
    }

    fun stop() {
        try {
            server?.stop()
            server = null
            _status.value = ServerStatus.STOPPED
            AppLogger.i(TAG, "HTTP Server 已停止")
        } catch (e: Exception) {
            AppLogger.e(TAG, "停止失败", e)
        }
    }

    fun restart(port: Int = Constants.REMOTE_HTTP_PORT, deviceName: String = ""): String? {
        stop()
        Thread.sleep(200)
        return start(port, deviceName)
    }

    fun getCurrentPort(): Int = currentPort
    fun isRunning(): Boolean = _status.value == ServerStatus.RUNNING

    // ===== IP 获取 =====

    private fun reportError(msg: String): String? {
        AppLogger.w(TAG, msg)
        _status.value = ServerStatus.ERROR
        return null
    }

    private fun getLanIpAddress(): String? {
        try {
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt != 0) {
                val ip = InetAddress.getByAddress(
                    byteArrayOf(
                        (ipInt and 0xff).toByte(),
                        (ipInt shr 8 and 0xff).toByte(),
                        (ipInt shr 16 and 0xff).toByte(),
                        (ipInt shr 24 and 0xff).toByte()
                    )
                ).hostAddress
                if (ip != null && isLanIp(ip)) return ip
            }
        } catch (_: Exception) {}
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr.address.size == 4) {
                        val ip = addr.hostAddress
                        if (ip != null && isLanIp(ip)) return ip
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun isLanIp(ip: String) =
        ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172.")

    // ===== NanoHTTPd 子类 =====

    private inner class RemoPhotoServer(
        hostname: String,
        port: Int,
        private val ctx: Context,
        private val deviceName: String
    ) : NanoHTTPD(hostname, port) {

        private val deps by lazy { ctx.dependencies }

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            AppLogger.d(TAG, "HTTP ${session.method} $uri")

            return when {
                uri == "/api" -> handlePing()
                uri == "/api/albums" -> handleAlbumsList()
                uri.matches(albumImagesRegex) -> {
                    val m = albumImagesRegex.find(uri)!!
                    handleImageList(m.groupValues[1].toLong(), session.parms)
                }
                uri.matches(imageThumbRegex) -> {
                    val id = imageThumbRegex.find(uri)!!.groupValues[1].toLong()
                    handleImageThumbnail(id)
                }
                uri.matches(imageInfoRegex) -> {
                    val id = imageInfoRegex.find(uri)!!.groupValues[1].toLong()
                    handleImageInfo(id)
                }
                uri.matches(imageStreamRegex) -> {
                    val id = imageStreamRegex.find(uri)!!.groupValues[1].toLong()
                    handleImageStream(id)
                }
                else -> jsonResponse(Response.Status.NOT_FOUND, """{"error":"Not Found"}""")
            }
        }

        // ===== Handlers =====

        private fun handlePing(): Response {
            return jsonResponse(
                Response.Status.OK,
                obj(
                    "status" to "ok",
                    "version" to "1.0.0",
                    "app" to "remoPhoto",
                    "deviceName" to deviceName.ifBlank { "remoPhoto" }
                )
            )
        }

        private fun handleAlbumsList(): Response {
            return try {
                val albums = runBlocking { deps.albumRepository.getAllAlbumsList() }
                val localRepoIds = runBlocking {
                    deps.repositoryDao.getAllRepositoriesList()
                        .filter { it.remoteConnectionId == null }
                        .mapTo(mutableSetOf()) { it.id }
                }
                // 只共享本机 SAF 仓库。远程同步数据若再次暴露，会形成 A→B→A 回环。
                val localAlbums = albums.filter { it.repositoryId in localRepoIds }
                AppLogger.i(
                    TAG,
                    "/api/albums: 本地相册=${localAlbums.size}, " +
                        "已拦截远程相册=${albums.size - localAlbums.size}"
                )
                val json = buildJsonArray(localAlbums) { album ->
                    val coverImageId = runBlocking {
                        deps.imageDao.getCoverImageId(album.id, album.coverImagePath)
                    }
                    obj(
                        "id" to album.id,
                        "name" to album.name,
                        "imageCount" to album.imageCount,
                        "repositoryId" to album.repositoryId,
                        "parentAlbumId" to album.parentAlbumId,
                        "coverImageId" to coverImageId
                    )
                }
                jsonResponse(Response.Status.OK, """{"albums":$json}""")
            } catch (e: Exception) {
                AppLogger.e(TAG, "/api/albums 错误", e)
                jsonResponse(Response.Status.INTERNAL_ERROR, """{"error":"Server Error"}""")
            }
        }

        private fun handleImageList(albumId: Long, params: Map<String, String>): Response {
            return try {
                val album = runBlocking { deps.albumRepository.getAlbumById(albumId) }
                    ?: return jsonResponse(Response.Status.NOT_FOUND, """{"error":"Album not found"}""")
                if (!isLocalRepository(album.repositoryId)) {
                    AppLogger.w(TAG, "拦截远程相册二次共享: albumId=$albumId")
                    return jsonResponse(Response.Status.FORBIDDEN, """{"error":"Remote relay forbidden"}""")
                }
                val page = params["page"]?.toIntOrNull() ?: 1
                val pageSize = params["pageSize"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
                val offset = (page - 1) * pageSize

                val images = runBlocking { deps.imageRepository.getImagesByAlbumPaged(albumId, pageSize, offset) }
                val totalCount = runBlocking { deps.imageRepository.getImageCountByAlbum(albumId) }

                val json = buildJsonArray(images) { img ->
                    obj(
                        "id" to img.id,
                        "fileName" to img.fileName,
                        "fileSize" to img.fileSize,
                        "lastModified" to img.lastModified,
                        "width" to img.width,
                        "height" to img.height,
                        "mimeType" to img.mimeType,
                        "albumId" to img.albumId,
                        "repositoryId" to img.repositoryId
                    )
                }
                jsonResponse(Response.Status.OK,
                    """{"images":$json,"totalCount":$totalCount,"page":$page,"pageSize":$pageSize}""")
            } catch (e: Exception) {
                AppLogger.e(TAG, "/api/album/$albumId/images 错误", e)
                jsonResponse(Response.Status.INTERNAL_ERROR, """{"error":"Server Error"}""")
            }
        }

        private fun handleImageStream(imageId: Long): Response {
            return try {
                val img = runBlocking { deps.imageRepository.getImageById(imageId) }
                    ?: return jsonResponse(Response.Status.NOT_FOUND, """{"error":"Image not found"}""")
                if (!isLocalRepository(img.repositoryId)) {
                    AppLogger.w(TAG, "拦截远程图片二次共享: imageId=$imageId")
                    return jsonResponse(Response.Status.FORBIDDEN, """{"error":"Remote relay forbidden"}""")
                }

                val inputStream = openImageStream(img)
                    ?: return jsonResponse(Response.Status.NOT_FOUND, """{"error":"File not accessible"}""")

                val bytes = inputStream.use { it.readBytes() }
                val mime = img.mimeType.ifEmpty { "image/jpeg" }
                newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
            } catch (e: Exception) {
                AppLogger.e(TAG, "/api/image/$imageId 错误", e)
                jsonResponse(Response.Status.INTERNAL_ERROR, """{"error":"Server Error"}""")
            }
        }

        private fun handleImageThumbnail(imageId: Long): Response {
            return try {
                val img = runBlocking { deps.imageRepository.getImageById(imageId) }
                    ?: return jsonResponse(Response.Status.NOT_FOUND, """{"error":"Image not found"}""")
                if (!isLocalRepository(img.repositoryId)) {
                    return jsonResponse(Response.Status.FORBIDDEN, """{"error":"Remote relay forbidden"}""")
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openImageStream(img)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return jsonResponse(Response.Status.UNSUPPORTED_MEDIA_TYPE, """{"error":"Unsupported image"}""")
                }
                var sample = 1
                while (bounds.outWidth / sample > 768 || bounds.outHeight / sample > 768) sample *= 2
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                val bitmap = openImageStream(img)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: return jsonResponse(Response.Status.NOT_FOUND, """{"error":"File not accessible"}""")
                val output = ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, output)
                bitmap.recycle()
                val bytes = output.toByteArray()
                AppLogger.d(TAG, "缩略图生成: imageId=$imageId sample=$sample bytes=${bytes.size}")
                newFixedLengthResponse(
                    Response.Status.OK,
                    "image/jpeg",
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "/api/image/$imageId/thumb 错误", e)
                jsonResponse(Response.Status.INTERNAL_ERROR, """{"error":"Server Error"}""")
            }
        }

        private fun handleImageInfo(imageId: Long): Response {
            return try {
                val img = runBlocking { deps.imageRepository.getImageById(imageId) }
                    ?: return jsonResponse(Response.Status.NOT_FOUND, """{"error":"Image not found"}""")
                if (!isLocalRepository(img.repositoryId)) {
                    AppLogger.w(TAG, "拦截远程图片信息二次共享: imageId=$imageId")
                    return jsonResponse(Response.Status.FORBIDDEN, """{"error":"Remote relay forbidden"}""")
                }

                val json = buildJsonObject {
                    obj(
                        "id" to img.id,
                        "fileName" to img.fileName,
                        "fileSize" to img.fileSize,
                        "lastModified" to img.lastModified,
                        "width" to img.width,
                        "height" to img.height,
                        "mimeType" to img.mimeType
                    )
                }
                jsonResponse(Response.Status.OK, json)
            } catch (e: Exception) {
                AppLogger.e(TAG, "/api/image/$imageId/info 错误", e)
                jsonResponse(Response.Status.INTERNAL_ERROR, """{"error":"Server Error"}""")
            }
        }

        // ===== Helpers =====

        private fun isLocalRepository(repositoryId: Long): Boolean = runBlocking {
            deps.repositoryDao.getRepositoryById(repositoryId)?.remoteConnectionId == null
        }

        private fun openImageStream(img: ImageEntity): java.io.InputStream? {
            return try {
                val path = img.filePath
                when {
                    path.startsWith("content://") ->
                        ctx.contentResolver.openInputStream(Uri.parse(path))
                    path.startsWith("/") -> FileInputStream(File(path))
                    else -> null
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "无法打开图片: ${img.filePath}", e)
                null
            }
        }

        private fun jsonResponse(status: Response.Status, json: String): Response {
            return newFixedLengthResponse(status, "application/json", json)
        }

        // ===== Mini JSON Builder (避免引入 Gson/Moshi 额外依赖) =====

        @Suppress("UNCHECKED_CAST")
        private fun <T> buildJsonArray(items: List<T>, elementFn: (T) -> String): String {
            return items.joinToString(",", "[", "]") { elementFn(it) }
        }

        private fun obj(vararg pairs: Pair<String, Any?>): String {
            return pairs.joinToString(",") { (k, v) ->
                "\"$k\":${jsonValue(v)}"
            }.let { "{$it}" }
        }

        private fun buildJsonObject(fn: () -> String): String = fn()

        private fun jsonValue(v: Any?): String = when (v) {
            null -> "null"
            is String -> "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            is Number, is Boolean -> v.toString()
            else -> "\"$v\""
        }
    }
}
