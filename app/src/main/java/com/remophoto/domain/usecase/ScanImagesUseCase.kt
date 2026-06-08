package com.remophoto.domain.usecase

import android.net.Uri
import com.remophoto.data.scanner.FileScanner

/**
 * 扫描图片用例（骨架）
 *
 * 协调 FileScanner 扫描仓库目录，提取图片元数据并存入数据库。
 * Phase 1 中实现完整的增量扫描、进度报告和错误处理。
 */
class ScanImagesUseCase(private val fileScanner: FileScanner) {

    /**
     * 执行全量扫描
     *
     * @param rootUri 根仓库 URI
     * @param onProgress 进度回调（0.0 ~ 1.0）
     * @return 扫描到的图片 URI 数量
     */
    suspend operator fun invoke(
        rootUri: Uri,
        onProgress: (Float) -> Unit = {}
    ): FileScanner.ScanResult {
        onProgress(0f)
        val result = fileScanner.scan(rootUri)
        onProgress(1f)
        return result
    }
}
