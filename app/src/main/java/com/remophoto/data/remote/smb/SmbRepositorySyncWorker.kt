package com.remophoto.data.remote.smb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.remophoto.MainActivity
import com.remophoto.R
import com.remophoto.RemoPhotoApp
import com.remophoto.data.local.entity.RemoteType
import com.remophoto.data.remote.RemoteDataException
import com.remophoto.util.AppLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class SmbRepositorySyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repositoryId = inputData.getLong(KEY_REPOSITORY_ID, -1L)
        if (repositoryId <= 0L) return Result.failure(errorData("仓库 ID 无效"))
        setForeground(createForegroundInfo(repositoryId, "准备刷新…", 0, null))
        val app = applicationContext as RemoPhotoApp
        val container = app.dependencyContainer
        val repository = container.repositoryDao.getRepositoryById(repositoryId)
            ?: return Result.failure(errorData("仓库不存在"))
        val connectionId = repository.remoteConnectionId
            ?: return Result.failure(errorData("不是远程仓库"))
        val connection = container.remoteConnectionDao.getConnectionById(connectionId)
            ?: return Result.failure(errorData("远程连接不存在"))
        if (connection.type != RemoteType.SMB) return Result.failure(errorData("不是 SMB 仓库"))

        AppLogger.i(
            TAG,
            "SMB 后台刷新开始: work=$id, connectionId=$connectionId, " +
                "repositoryId=$repositoryId, attempt=${runAttemptCount + 1}",
        )
        return try {
            val result = container.syncSmbRepositoryUseCase.execute(connection, repositoryId) { status ->
                setProgressAsync(
                    workDataOf(
                        KEY_REPOSITORY_ID to repositoryId,
                        KEY_PHASE to status.phase,
                        KEY_DISCOVERED to status.discoveredImages,
                        KEY_INDEXED to status.indexedImages,
                        KEY_TOTAL to (status.totalImages ?: -1),
                        KEY_DIRECTORIES to status.checkedDirectories,
                    )
                )
                setForegroundAsync(
                    createForegroundInfo(
                        repositoryId,
                        status.phase,
                        status.indexedImages.takeIf { status.totalImages != null }
                            ?: status.discoveredImages,
                        status.totalImages,
                    )
                )
            }
            AppLogger.i(
                TAG,
                "SMB 后台刷新完成: work=$id, connectionId=$connectionId, " +
                    "repositoryId=$repositoryId, images=${result.imageCount}",
            )
            Result.success(
                workDataOf(
                    KEY_REPOSITORY_ID to repositoryId,
                    KEY_TOTAL to result.imageCount,
                    KEY_ALBUMS to result.albumCount,
                )
            )
        } catch (cancelled: CancellationException) {
            AppLogger.i(TAG, "SMB 后台刷新取消: work=$id, repositoryId=$repositoryId")
            throw cancelled
        } catch (error: Throwable) {
            val retry = SmbSyncRetryPolicy.shouldRetry(error, runAttemptCount)
            val category = (error as? RemoteDataException)?.category?.name
                ?: SmbErrorMapper.category(error).name
            AppLogger.e(
                TAG,
                "SMB 后台刷新失败: work=$id, connectionId=$connectionId, repositoryId=$repositoryId, " +
                    "attempt=${runAttemptCount + 1}, retry=$retry, category=$category, " +
                    "cause=${error.javaClass.simpleName}",
            )
            if (retry) Result.retry() else Result.failure(errorData(category))
        }
    }

    private fun createForegroundInfo(
        repositoryId: Long,
        phase: String,
        current: Int,
        total: Int?,
    ): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SMB 仓库刷新", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            applicationContext,
            repositoryId.toInt(),
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (total != null && total > 0) "$phase：$current / $total" else "$phase：已发现 $current 张"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("remoPhoto 正在刷新 SMB 仓库")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .apply {
                if (total != null && total > 0) setProgress(total, current, false)
                else setProgress(0, 0, true)
            }
            .build()
        return ForegroundInfo(
            NOTIFICATION_BASE + repositoryId.toInt(),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun errorData(category: String) = workDataOf(KEY_ERROR to category)

    companion object {
        private const val TAG = "SmbRepositorySyncWorker"
        private const val CHANNEL_ID = "smb_repository_sync"
        private const val NOTIFICATION_BASE = 4000
        const val WORK_TAG = "smb_repository_sync"
        const val KEY_REPOSITORY_ID = "repository_id"
        const val KEY_PHASE = "phase"
        const val KEY_DISCOVERED = "discovered"
        const val KEY_INDEXED = "indexed"
        const val KEY_TOTAL = "total"
        const val KEY_DIRECTORIES = "directories"
        const val KEY_ALBUMS = "albums"
        const val KEY_ERROR = "error"

        fun uniqueName(repositoryId: Long) = "smb_repository_sync_$repositoryId"
        fun repositoryTag(repositoryId: Long) = "smb_repository_sync_repo_$repositoryId"

        fun request(repositoryId: Long): OneTimeWorkRequest = OneTimeWorkRequestBuilder<SmbRepositorySyncWorker>()
            .setInputData(workDataOf(KEY_REPOSITORY_ID to repositoryId))
            // SMB is a user-configured LAN endpoint. API 29 may expose a usable LAN while
            // NetworkType.CONNECTED remains unsatisfied because internet validation failed.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .addTag(repositoryTag(repositoryId))
            .build()

        fun enqueue(context: Context, repositoryId: Long, replaceRunning: Boolean = true) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(repositoryId),
                if (replaceRunning) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request(repositoryId),
            )
        }

        fun cancel(context: Context, repositoryId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(repositoryId))
        }
    }
}
