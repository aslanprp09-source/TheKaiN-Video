package com.thekain.video

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.thekain.video.data.DownloadTask
import com.thekain.video.domain.*
import com.thekain.video.presentation.localized
import com.thekain.video.transfer.EngineResult
import kotlinx.coroutines.*

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val graph get() = (applicationContext as TheKaiNApp).graph
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val task = graph.repository.get(inputData.getString("job_id") ?: error("Missing job id"))
        return foreground(task)
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString("job_id") ?: return Result.failure()
        try {
            graph.recoveryReady.await()
            val initial = graph.repository.get(id)
            if (initial.state in setOf(TransferState.COMPLETED, TransferState.CANCELED, TransferState.FAILED, TransferState.PAUSED)) return Result.success()
            setForeground(foreground(initial))
            var lastUpdate = 0L
            var lastState: TransferState? = null
            val result = graph.engine.run(id) { task ->
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 750 || task.state != lastState) {
                    setForeground(foreground(task))
                    lastUpdate = now
                    lastState = task.state
                }
            }
            if (result == EngineResult.RETRY) return Result.retry()
            terminal(graph.repository.get(id))
            return if (result == EngineResult.FAILED) Result.failure() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                val task = graph.repository.get(id)
                if (task.state in setOf(TransferState.QUEUED, TransferState.DOWNLOADING, TransferState.PAUSING,
                        TransferState.VERIFYING, TransferState.PUBLISHING, TransferState.RETRY_WAIT))
                    graph.repository.change(id) { it.copy(state = TransferState.FAILED, error = TransferError.BACKGROUND_RESTRICTED) }
            }
            return Result.failure()
        }
    }

    private fun foreground(task: DownloadTask): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("downloads", applicationContext.localized().getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getBroadcast(applicationContext, task.id.hashCode(),
            Intent(applicationContext, JobActionReceiver::class.java).setAction("cancel").putExtra("job_id", task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val busy = task.state in setOf(TransferState.VERIFYING, TransferState.PUBLISHING) || task.percent == null
        val notification = NotificationCompat.Builder(applicationContext, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(task.name)
            .setContentText(applicationContext.localized().getString(task.state.label()))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, task.percent ?: 0, busy)
            .apply { if (task.state != TransferState.PUBLISHING) addAction(0, applicationContext.localized().getString(R.string.cancel), cancel) }
            .build()
        val notificationId = (task.id.hashCode() and 0x0fffffff).coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(notificationId, notification)
    }

    private fun terminal(task: DownloadTask) {
        if (task.state !in setOf(TransferState.COMPLETED, TransferState.FAILED)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = if (task.state == TransferState.COMPLETED && task.outputUri != null)
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(task.outputUri), task.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        else Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(applicationContext, task.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "downloads")
            .setSmallIcon(if (task.state == TransferState.COMPLETED) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(task.name).setContentText(applicationContext.localized().getString(task.error?.label() ?: task.state.label()))
            .setContentIntent(pending).setAutoCancel(true).build()
        applicationContext.getSystemService(NotificationManager::class.java).notify((task.id.hashCode() and 0x0fffffff) or 0x10000000, notification)
    }
}

class JobActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("job_id") ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val graph = (context.applicationContext as TheKaiNApp).graph
                if (intent.action == "cancel") graph.cancel(id)
            } finally { pending.finish() }
        }
    }
}
