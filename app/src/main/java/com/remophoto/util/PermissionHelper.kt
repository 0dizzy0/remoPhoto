package com.remophoto.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * SAF（Storage Access Framework）权限工具类
 *
 * 封装 Android SAF 目录选择、持久化 URI 权限管理和文件访问逻辑。
 * 仅支持 Android 10（API 29）及以上。
 *
 * 用法：
 * ```
 * val helper = PermissionHelper(activity)
 * helper.openDirectoryPicker { uri -> /* 处理选中的目录 URI */ }
 * ```
 */
class PermissionHelper(private val activity: ComponentActivity) {

    /** SAF 目录选择器 launcher */
    private var directoryPickerLauncher: ActivityResultLauncher<Uri?>? = null
    private var onDirectorySelected: ((Uri) -> Unit)? = null

    /** 通知权限 launcher（Android 13+） */
    private var notificationPermissionLauncher: ActivityResultLauncher<String>? = null

    /**
     * 注册 SAF 目录选择器
     *
     * 需要在 Activity.onCreate 或 onStart 中调用。
     */
    fun registerDirectoryPicker(onResult: (Uri) -> Unit) {
        onDirectorySelected = onResult
        directoryPickerLauncher = activity.activityResultRegistry.register(
            "saf_directory_picker",
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            uri?.let {
                // 持久化 URI 权限
                persistUriPermission(it)
                onResult(it)
            }
        }
    }

    /**
     * 注册通知权限请求（Android 13+）
     */
    fun registerNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher = activity.activityResultRegistry.register(
                "notification_permission",
                ActivityResultContracts.RequestPermission()
            ) { /* 结果回调 */ }
        }
    }

    /**
     * 打开系统目录选择器
     *
     * 用户选择一个目录后，系统通过注册的回调返回 URI。
     */
    fun openDirectoryPicker() {
        directoryPickerLauncher?.launch(null)
    }

    /**
     * 请求通知权限（Android 13+）
     */
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher?.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * 持久化 URI 访问权限
     *
     * 调用 ContentResolver.takePersistableUriPermission 持久化授权，
     * 确保应用重启、设备重启后仍可访问该目录。
     */
    fun persistUriPermission(uri: Uri) {
        try {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // 某些 URI 不支持持久化（如虚拟文件），仅记录不含完整 URI 的诊断信息。
            AppLogger.w("PermissionHelper", "无法持久化 URI 权限: authority=${uri.authority}")
        }
    }

    /**
     * 验证 URI 权限是否仍然有效
     *
     * 通过列出目录内容来验证权限。
     * 如果权限已失效（如用户卸载重装后），返回 false。
     */
    fun isUriPermissionValid(uri: Uri): Boolean {
        return try {
            val documentFile = DocumentFile.fromTreeUri(activity, uri)
            documentFile?.listFiles() != null
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取 URI 对应的文件显示名称
     */
    fun getFileName(uri: Uri): String? {
        return queryColumn(uri, OpenableColumns.DISPLAY_NAME)
    }

    /**
     * 获取 URI 对应的文件大小
     */
    fun getFileSize(uri: Uri): Long {
        val size = queryColumn(uri, OpenableColumns.SIZE)
        return size?.toLongOrNull() ?: 0L
    }

    /**
     * 通过 DocumentFile 递归列出目录下所有文件
     *
     * @param uri 目录 URI
     * @param extensions 过滤的文件扩展名集合（小写），null 表示不过滤
     * @return 文件 URI 列表
     */
    fun listAllFiles(uri: Uri, extensions: Set<String>? = null): List<Uri> {
        val files = mutableListOf<Uri>()
        val root = DocumentFile.fromTreeUri(activity, uri) ?: return files
        collectFiles(root, extensions, files)
        return files
    }

    /** 递归收集目录下所有文件 */
    private fun collectFiles(
        directory: DocumentFile,
        extensions: Set<String>?,
        result: MutableList<Uri>
    ) {
        val children = directory.listFiles()
        for (child in children) {
            if (child.isDirectory) {
                collectFiles(child, extensions, result)
            } else if (child.isFile) {
                val name = child.name ?: continue
                if (extensions == null || name.substringAfterLast('.', "").lowercase() in extensions) {
                    result.add(child.uri)
                }
            }
        }
    }

    /**
     * 尝试从 content:// URI 获取实际文件路径
     *
     * 注意：SAF URI 不保证有实际路径，部分场景返回 null。
     */
    fun getRealPathFromUri(uri: Uri): String? {
        // 已经是 file:// 路径
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return uri.path
        }

        // 尝试通过 DocumentsContract 解析
        return try {
            val docId = DocumentsContract.getDocumentId(uri)
            if (docId.startsWith("primary:")) {
                val path = docId.substringAfter("primary:")
                if (path.startsWith("/")) path else "/$path"
                "${android.os.Environment.getExternalStorageDirectory()}$path"
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 查询 content:// URI 的指定列
     */
    private fun queryColumn(uri: Uri, column: String): String? {
        return try {
            activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(column)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
