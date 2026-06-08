package com.remophoto.data.scanner

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.remophoto.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件系统扫描器（骨架）
 *
 * 递归遍历根仓库目录，识别图片文件并提取元数据。
 * Phase 1 中接入 Room 数据库和完整的增量扫描逻辑。
 *
 * @param context Application Context
 */
class FileScanner(private val context: Context) {

    /**
     * 扫描结果数据类
     */
    data class ScanResult(
        /** 发现的图片文件 URI 列表 */
        val imageUris: List<Uri>,
        /** 发现的子目录 URI 列表 */
        val subDirectories: List<Uri>,
        /** 扫描的文件总数 */
        val totalFiles: Int,
        /** 扫描耗时（毫秒） */
        val elapsedMs: Long
    )

    /**
     * 扫描指定目录（同步版本，应由调用方调度到 IO 线程）
     *
     * @param rootUri 根目录 URI（SAF 授权后的 content:// URI）
     * @return 扫描结果
     */
    suspend fun scan(rootUri: Uri): ScanResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val images = mutableListOf<Uri>()
        val directories = mutableListOf<Uri>()

        val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
        if (rootDoc != null) {
            scanDirectory(rootDoc, images, directories, depth = 0)
        }

        val elapsed = System.currentTimeMillis() - startTime
        ScanResult(
            imageUris = images,
            subDirectories = directories,
            totalFiles = images.size + directories.size,
            elapsedMs = elapsed
        )
    }

    /**
     * 递归扫描单个目录
     *
     * @param directory DocumentFile 目录对象
     * @param images 累积的图片文件 URI 列表
     * @param directories 累积的子目录 URI 列表
     * @param depth 当前递归深度（限制最大深度保护性能）
     */
    private fun scanDirectory(
        directory: DocumentFile,
        images: MutableList<Uri>,
        directories: MutableList<Uri>,
        depth: Int
    ) {
        // 目录深度保护（最深 10 层，超过会非常大）
        if (depth > 10) return

        val children = directory.listFiles()
        for (child in children) {
            when {
                child.isDirectory -> {
                    directories.add(child.uri)
                    scanDirectory(child, images, directories, depth + 1)
                }
                child.isFile && isImageFile(child) -> {
                    images.add(child.uri)
                }
            }
        }
    }

    /**
     * 判断文件是否为支持的图片格式
     */
    private fun isImageFile(file: DocumentFile): Boolean {
        val name = file.name ?: return false
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in Constants.SUPPORTED_IMAGE_EXTENSIONS) return false

        // 可选：检查 MIME 类型
        val mimeType = file.type
        return mimeType == null || mimeType.startsWith("image/")
    }

    /**
     * 从 content:// URI 提取文件元数据
     */
    fun extractMetadata(uri: Uri): FileMetadata? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)

                    FileMetadata(
                        uri = uri,
                        fileName = if (nameIdx >= 0) cursor.getString(nameIdx) else "unknown",
                        fileSize = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    )
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 文件元数据
     */
    data class FileMetadata(
        val uri: Uri,
        val fileName: String,
        val fileSize: Long
    )
}
