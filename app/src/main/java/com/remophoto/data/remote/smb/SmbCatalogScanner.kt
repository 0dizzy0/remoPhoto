package com.remophoto.data.remote.smb

import com.remophoto.data.local.entity.RemoteConnectionEntity
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.remote.RemoteDataException
import com.remophoto.data.remote.RemoteErrorCategory
import com.remophoto.util.AppLogger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class SmbCatalogLimits(
    val maxDepth: Int = 32,
    val maxEntries: Int = 250_000,
    val maxSpoolBytes: Long = 256L * 1024L * 1024L,
) {
    init {
        require(maxDepth in 1..128)
        require(maxEntries > 0)
        require(maxSpoolBytes > 0L)
    }
}

data class SmbAlbumSnapshot(
    val relativePath: String,
    val name: String,
    val parentRelativePath: String?,
    val imageCount: Int,
    val lastModified: Long,
    val coverOpaqueKey: String?,
    val coverVersionToken: String?,
)

data class SmbCatalogSnapshot(
    val spoolFile: File,
    val albums: List<SmbAlbumSnapshot>,
    val imageCount: Int,
    val checkedDirectories: Int,
    val skippedReparsePoints: Int,
)

data class SmbSpoolMediaRecord(
    val parentRelativePath: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Long,
    val mimeType: String,
    val opaqueMediaKey: String,
    val versionToken: String,
)

class SmbCatalogScanner(
    private val sessionManager: SmbSessionManager,
    private val limits: SmbCatalogLimits = SmbCatalogLimits(),
) {
    suspend fun scanToSpool(
        connection: RemoteConnectionEntity,
        spoolFile: File,
        onProgress: (directories: Int, images: Int) -> Unit = { _, _ -> },
    ): SmbCatalogSnapshot = withContext(Dispatchers.IO) {
        require(connection.type == RemoteType.SMB) { "连接类型不是 SMB" }
        require(connection.id > 0L) { "SMB 连接必须已持久化" }
        spoolFile.parentFile?.mkdirs()
        val startedAt = System.currentTimeMillis()
        val stateByPath = linkedMapOf<String, MutableDirectoryState>()
        var imageCount = 0
        var checkedDirectories = 0
        var totalEntries = 0
        var skippedReparsePoints = 0

        try {
            val countingOutput = CountingOutputStream(BufferedOutputStream(spoolFile.outputStream()))
            DataOutputStream(countingOutput).use { output ->
                sessionManager.executeCatalogScan(connection) { session ->
                    val queue = ArrayDeque<QueuedDirectory>()
                    queue.add(QueuedDirectory(relativePath = "", name = connection.displayName, depth = 0))
                    stateByPath[""] = MutableDirectoryState(connection.displayName, null, 0)

                    while (queue.isNotEmpty()) {
                        coroutineContext.ensureActive()
                        val directory = queue.removeFirst()
                        if (directory.depth > limits.maxDepth) resourceLimit()
                        val entries = runInterruptible(Dispatchers.IO) {
                            session.list(SmbPathCodec.serverPath(connection.rootPath, directory.relativePath))
                        }
                        checkedDirectories++
                        totalEntries += entries.size
                        if (totalEntries > limits.maxEntries) resourceLimit()

                        // SMBJ 已为当前目录构造条目列表；直接单遍消费，避免大型目录再次复制和排序。
                        for (entry in entries) {
                            coroutineContext.ensureActive()
                            if (entry.name == "." || entry.name == "..") continue
                            val name = SmbPathCodec.validateEntryName(entry.name)
                            val relativePath = SmbPathCodec.joinRelative(directory.relativePath, name)
                            if (entry.isDirectory) {
                                if (entry.isReparsePoint) {
                                    skippedReparsePoints++
                                    continue
                                }
                                if (stateByPath.containsKey(relativePath)) continue
                                stateByPath[relativePath] = MutableDirectoryState(
                                    name = name,
                                    parentPath = directory.relativePath,
                                    depth = directory.depth + 1,
                                )
                                queue.add(QueuedDirectory(relativePath, name, directory.depth + 1))
                            } else {
                                val mime = SmbImageFormats.mimeType(name) ?: continue
                                val opaqueKey = SmbPathCodec.opaqueKey(relativePath)
                                val version = SmbPathCodec.versionToken(entry.size, entry.lastModified)
                                writeRecord(
                                    output,
                                    SmbSpoolMediaRecord(
                                        parentRelativePath = directory.relativePath,
                                        fileName = name,
                                        fileSize = entry.size,
                                        lastModified = entry.lastModified,
                                        mimeType = mime,
                                        opaqueMediaKey = opaqueKey,
                                        versionToken = version,
                                    ),
                                )
                                val state = checkNotNull(stateByPath[directory.relativePath])
                                state.ownImageCount++
                                state.lastModified = maxOf(state.lastModified, entry.lastModified)
                                if (state.coverOpaqueKey == null) {
                                    state.coverOpaqueKey = opaqueKey
                                    state.coverVersionToken = version
                                }
                                imageCount++
                                if (countingOutput.count > limits.maxSpoolBytes) resourceLimit()
                                if (imageCount % PROGRESS_IMAGE_INTERVAL == 0) {
                                    onProgress(checkedDirectories, imageCount)
                                }
                            }
                        }
                        if (checkedDirectories % PROGRESS_DIRECTORY_INTERVAL == 0) {
                            output.flush()
                            onProgress(checkedDirectories, imageCount)
                        }
                    }
                }
            }

            // 由深到浅传播“后代含图片”和封面，纯空目录不会进入最终快照。
            stateByPath.entries.sortedByDescending { it.value.depth }.forEach { (path, state) ->
                if (state.ownImageCount > 0 || state.descendantHasImages) {
                    state.parentPath?.let { parentPath ->
                        stateByPath[parentPath]?.let { parent ->
                            parent.descendantHasImages = true
                            parent.lastModified = maxOf(parent.lastModified, state.lastModified)
                            if (parent.coverOpaqueKey == null) {
                                parent.coverOpaqueKey = state.coverOpaqueKey
                                parent.coverVersionToken = state.coverVersionToken
                            }
                        }
                    }
                }
            }
            val albums = stateByPath.mapNotNull { (path, state) ->
                // 扫描起点是仓库边界，不是用户创建的相册：不生成“根目录”占位项。
                // 若起点直接包含图片，则使用清晰的虚拟相册承载，避免根级图片丢失。
                if (path.isEmpty() && state.ownImageCount == 0) return@mapNotNull null
                if (path.isNotEmpty() && state.ownImageCount == 0 && !state.descendantHasImages) {
                    return@mapNotNull null
                }
                SmbAlbumSnapshot(
                    relativePath = path,
                    name = if (path.isEmpty()) ROOT_IMAGES_ALBUM_NAME else state.name,
                    // 共享根目录不再是相册，直属目录应直接成为仓库顶层相册。
                    parentRelativePath = state.parentPath?.takeIf(String::isNotEmpty),
                    imageCount = state.ownImageCount,
                    lastModified = state.lastModified,
                    coverOpaqueKey = state.coverOpaqueKey,
                    coverVersionToken = state.coverVersionToken,
                )
            }
            onProgress(checkedDirectories, imageCount)
            AppLogger.i(
                TAG,
                "SMB 快照枚举完成: connectionId=${connection.id}, dirs=$checkedDirectories, " +
                    "albums=${albums.size}, images=$imageCount, reparseSkipped=$skippedReparsePoints, " +
                    "spoolBytes=${spoolFile.length()}, elapsedMs=${System.currentTimeMillis() - startedAt}",
            )
            SmbCatalogSnapshot(
                spoolFile = spoolFile,
                albums = albums,
                imageCount = imageCount,
                checkedDirectories = checkedDirectories,
                skippedReparsePoints = skippedReparsePoints,
            )
        } catch (error: Throwable) {
            if (spoolFile.exists() && !spoolFile.delete()) {
                AppLogger.w(TAG, "SMB 失败快照临时文件清理失败: connectionId=${connection.id}")
            }
            throw error
        }
    }

    companion object {
        private const val TAG = "SmbCatalogScanner"
        private const val PROGRESS_DIRECTORY_INTERVAL = 10
        private const val PROGRESS_IMAGE_INTERVAL = 500
        internal const val ROOT_IMAGES_ALBUM_NAME = "未分类图片"

        fun readSpool(file: File, block: (SmbSpoolMediaRecord) -> Unit) {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                while (true) {
                    val record = try {
                        readRecord(input)
                    } catch (_: EOFException) {
                        break
                    }
                    block(record)
                }
            }
        }

        suspend fun readSpoolBatches(
            file: File,
            batchSize: Int,
            block: suspend (List<SmbSpoolMediaRecord>) -> Unit,
        ) {
            require(batchSize > 0)
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                val batch = ArrayList<SmbSpoolMediaRecord>(batchSize)
                while (true) {
                    coroutineContext.ensureActive()
                    val record = try {
                        readRecord(input)
                    } catch (_: EOFException) {
                        break
                    }
                    batch += record
                    if (batch.size >= batchSize) {
                        block(batch.toList())
                        batch.clear()
                    }
                }
                if (batch.isNotEmpty()) block(batch)
            }
        }

        private fun writeRecord(output: DataOutputStream, record: SmbSpoolMediaRecord) {
            output.writeUTF(record.parentRelativePath)
            output.writeUTF(record.fileName)
            output.writeLong(record.fileSize)
            output.writeLong(record.lastModified)
            output.writeUTF(record.mimeType)
            output.writeUTF(record.opaqueMediaKey)
            output.writeUTF(record.versionToken)
        }

        private fun readRecord(input: DataInputStream): SmbSpoolMediaRecord = SmbSpoolMediaRecord(
            parentRelativePath = input.readUTF(),
            fileName = input.readUTF(),
            fileSize = input.readLong(),
            lastModified = input.readLong(),
            mimeType = input.readUTF(),
            opaqueMediaKey = input.readUTF(),
            versionToken = input.readUTF(),
        )
    }

    private data class QueuedDirectory(val relativePath: String, val name: String, val depth: Int)

    private data class MutableDirectoryState(
        val name: String,
        val parentPath: String?,
        val depth: Int,
        var ownImageCount: Int = 0,
        var descendantHasImages: Boolean = false,
        var lastModified: Long = 0L,
        var coverOpaqueKey: String? = null,
        var coverVersionToken: String? = null,
    )

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            out.write(value)
            count++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            count += length
        }
    }

    private fun resourceLimit(): Nothing = throw RemoteDataException(
        RemoteErrorCategory.RESOURCE_LIMIT,
        "SMB 扫描超过安全资源上限",
    )
}

object SmbImageFormats {
    private val mimeByExtension = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
    )

    fun mimeType(fileName: String): String? = fileName.substringAfterLast('.', "")
        .lowercase()
        .takeIf(String::isNotEmpty)
        ?.let(mimeByExtension::get)
}
