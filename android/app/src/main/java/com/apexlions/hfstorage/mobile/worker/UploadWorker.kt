package com.apexlions.hfstorage.mobile.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.apexlions.hfstorage.mobile.data.HfApiClient
import com.apexlions.hfstorage.mobile.data.TokenStore
import com.apexlions.hfstorage.mobile.data.UploadDiagnostics
import com.apexlions.hfstorage.mobile.data.UploadEngine
import com.apexlions.hfstorage.mobile.data.UploadJob
import com.apexlions.hfstorage.mobile.data.UploadQueue
import kotlinx.coroutines.CancellationException
import kotlin.math.absoluteValue

class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_STAGE = "stage"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_MESSAGE = "message"
        const val KEY_LOG_PATH = "log_path"
        const val CHANNEL_ID = "hf_storage_uploads"
        const val NOTIFICATION_ID = 4701
        private const val TAG = "HFStorageUpload"
    }

    override suspend fun doWork(): Result {
        var token: String? = null
        var job: UploadJob? = null
        val id = inputData.getString(KEY_JOB_ID)
            ?: return Result.failure(errorData("İş kimliği eksik."))
        val queue = UploadQueue(applicationContext)

        return try {
            token = TokenStore(applicationContext).load()
                ?: throw IllegalStateException("Kayıtlı token bulunamadı. Uygulamaya yeniden giriş yapın.")
            job = queue.load(id)

            // Android 14+ requires the foreground service type both in the
            // manifest and in ForegroundInfo. Omitting it caused the process to
            // be terminated as soon as an upload started on Android 14-16.
            setForeground(createForeground(id, "Xet yüklemesi hazırlanıyor", 0, job.items.size))

            UploadEngine(applicationContext, HfApiClient(token), token).upload(job) { progress ->
                val data = Data.Builder()
                    .putString(KEY_STAGE, progress.stage)
                    .putInt(KEY_CURRENT, progress.current)
                    .putInt(KEY_TOTAL, progress.total)
                    .putString(KEY_MESSAGE, progress.message)
                    .build()
                setProgress(data)
                setForeground(createForeground(id, progress.message, progress.current, progress.total))
            }
            queue.delete(id)
            Result.success(
                Data.Builder()
                    .putString(KEY_MESSAGE, "Xet yüklemesi tamamlandı")
                    .putInt(KEY_CURRENT, job.items.size)
                    .putInt(KEY_TOTAL, job.items.size)
                    .build(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Upload failed for job=$id", error)
            val log = UploadDiagnostics.write(applicationContext, job, token, error)
            val message = buildString {
                append(error.message ?: error::class.java.simpleName)
                if (log != null) append("\nTanı günlüğü: ${log.absolutePath}")
            }
            Result.failure(errorData(message, log?.absolutePath))
        }
    }

    private fun createForeground(jobId: String, message: String, current: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "HF Storage Xet aktarımları", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("HF Storage • Xet yükleme")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(total.coerceAtLeast(1), current.coerceIn(0, total.coerceAtLeast(1)), total <= 0)
            .build()
        val notificationId = NOTIFICATION_ID + (jobId.hashCode().absoluteValue % 1000)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun errorData(message: String, logPath: String? = null): Data = Data.Builder()
        .putString(KEY_MESSAGE, message)
        .putString(KEY_LOG_PATH, logPath)
        .build()
}
