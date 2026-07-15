package com.apexlions.hfstorage.mobile.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.apexlions.hfstorage.mobile.data.HfApiClient
import com.apexlions.hfstorage.mobile.data.TokenStore
import com.apexlions.hfstorage.mobile.data.UploadEngine
import com.apexlions.hfstorage.mobile.data.UploadQueue

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
        const val CHANNEL_ID = "hf_storage_uploads"
        const val NOTIFICATION_ID = 4701
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_JOB_ID) ?: return Result.failure(errorData("İş kimliği eksik."))
        val queue = UploadQueue(applicationContext)
        val token = TokenStore(applicationContext).load()
            ?: return Result.failure(errorData("Kayıtlı token bulunamadı. Uygulamaya yeniden giriş yapın."))
        val job = runCatching { queue.load(id) }.getOrElse {
            return Result.failure(errorData(it.message ?: "Yükleme işi okunamadı."))
        }
        setForeground(createForeground("Yükleme hazırlanıyor", 0, job.items.size))

        return try {
            UploadEngine(applicationContext.contentResolver, HfApiClient(token)).upload(job) { progress ->
                val data = Data.Builder()
                    .putString(KEY_STAGE, progress.stage)
                    .putInt(KEY_CURRENT, progress.current)
                    .putInt(KEY_TOTAL, progress.total)
                    .putString(KEY_MESSAGE, progress.message)
                    .build()
                setProgressAsync(data)
                setForegroundAsync(createForeground(progress.message, progress.current, progress.total))
            }
            queue.delete(id)
            Result.success(
                Data.Builder()
                    .putString(KEY_MESSAGE, "Yükleme tamamlandı")
                    .putInt(KEY_CURRENT, job.items.size)
                    .putInt(KEY_TOTAL, job.items.size)
                    .build(),
            )
        } catch (error: Throwable) {
            Result.failure(errorData(error.message ?: error::class.java.simpleName))
        }
    }

    private fun createForeground(message: String, current: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "HF Storage aktarımları", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("HF Storage yükleme")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), current.coerceAtLeast(0), total <= 0)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun errorData(message: String): Data = Data.Builder().putString(KEY_MESSAGE, message).build()
}
