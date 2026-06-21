package com.remophoto.data.scanner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.remophoto.MainActivity
import com.remophoto.R
import com.remophoto.RemoPhotoApp
import com.remophoto.util.AppLogger
import kotlinx.coroutines.CancellationException

class RepositoryScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repoId = inputData.getLong(KEY_REPOSITORY_ID, -1L)
        if (repoId <= 0L) return Result.failure(workDataOf(KEY_ERROR to "仓库 ID 无效"))
        setForeground(createForegroundInfo(repoId, "准备扫描…", 0, null))
        val app = applicationContext as RemoPhotoApp
        val repo = app.dependencyContainer.repositoryDao.getRepositoryById(repoId)
            ?: return Result.failure(workDataOf(KEY_ERROR to "仓库不存在"))
        AppLogger.i(TAG, "前台扫描开始: work=$id repo=$repoId attempt=$runAttemptCount")
        return try {
            val total = app.dependencyContainer.scanImagesUseCase.executeFullScan(
                repoId = repoId,
                rootUri = Uri.parse(repo.uriString),
                onStatus = { status ->
                    val remaining = status.totalImages?.let { (it - status.indexedImages).coerceAtLeast(0) }
                    val progress = workDataOf(
                        KEY_REPOSITORY_ID to repoId,
                        KEY_PHASE to status.phase,
                        KEY_DISCOVERED to status.discoveredImages,
                        KEY_INDEXED to status.indexedImages,
                        KEY_TOTAL to (status.totalImages ?: -1),
                        KEY_REMAINING to (remaining ?: -1),
                        KEY_DIRECTORIES to status.checkedDirectories
                    )
                    setProgressAsync(progress)
                    setForegroundAsync(
                        createForegroundInfo(
                            repoId,
                            status.phase,
                            if (status.totalImages == null) status.discoveredImages else status.indexedImages,
                            status.totalImages
                        )
                    )
                }
            )
            AppLogger.i(TAG, "前台扫描完成: work=$id repo=$repoId images=$total")
            Result.success(workDataOf(KEY_REPOSITORY_ID to repoId, KEY_TOTAL to total))
        } catch (cancelled: CancellationException) {
            AppLogger.i(TAG, "前台扫描取消: work=$id repo=$repoId")
            throw cancelled
        } catch (e: Exception) {
            AppLogger.e(TAG, "前台扫描失败: work=$id repo=$repoId", e)
            Result.failure(workDataOf(KEY_REPOSITORY_ID to repoId, KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun createForegroundInfo(repoId: Long, phase: String, indexed: Int, total: Int?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "仓库扫描", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            applicationContext,
            repoId.toInt(),
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (total != null && total > 0) "$phase：$indexed / $total" else "$phase：已发现 $indexed 张"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("remoPhoto 正在扫描仓库")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .apply {
                if (total != null && total > 0) setProgress(total, indexed, false)
                else setProgress(0, 0, true)
            }
            .build()
        return ForegroundInfo(
            NOTIFICATION_BASE + repoId.toInt(),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    companion object {
        private const val TAG = "RepositoryScanWorker"
        private const val CHANNEL_ID = "repository_scan"
        private const val NOTIFICATION_BASE = 3000
        const val WORK_TAG = "repository_scan"
        const val KEY_REPOSITORY_ID = "repository_id"
        const val KEY_PHASE = "phase"
        const val KEY_DISCOVERED = "discovered"
        const val KEY_INDEXED = "indexed"
        const val KEY_TOTAL = "total"
        const val KEY_REMAINING = "remaining"
        const val KEY_DIRECTORIES = "directories"
        const val KEY_ERROR = "error"
        fun uniqueName(repoId: Long) = "repository_scan_$repoId"
        fun repositoryTag(repoId: Long) = "repository_scan_repo_$repoId"
    }
}
