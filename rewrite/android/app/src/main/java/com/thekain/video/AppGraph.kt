package com.thekain.video

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.*
import com.thekain.video.data.*
import com.thekain.video.domain.*
import com.thekain.video.storage.*
import com.thekain.video.transfer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.UUID
import com.thekain.video.source.HttpsSourceAnalyzer
import com.thekain.video.source.SourceAnalyzer

data class Submission(val operation: String = "", val busy: Boolean = false,
                      val accepted: String? = null, val error: TransferError? = null)

class TheKaiNApp : Application() {
    val graph by lazy { AppGraph(this) }
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { graph.recover() }
            finally { graph.recoveryReady.complete(Unit) }
        }
    }
}

class AppGraph(val context: Context) {
    val recoveryReady = CompletableDeferred<Unit>()
    private val actions = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableSubmission = MutableStateFlow(Submission())
    val submission = mutableSubmission.asStateFlow()
    val db = Room.databaseBuilder(context, DownloadsDb::class.java, "downloads.db").build()
    val repository = DownloadRepository(db)
    val verifier = MediaVerifier(context)
    val publisher = MediaPublisher(context, verifier)
    // Dependency boundary for owned TLS fixtures; production always uses system trust.
    @Volatile var connections: Connections = systemConnections
    val sourceAnalyzer = SourceAnalyzer { input -> HttpsSourceAnalyzer(connections).analyze(input) }
    val engine by lazy { DownloadEngine(repository, File(context.filesDir, "transfers"),
        object : TransferAdapter {
            override suspend fun fetch(task: DownloadTask, stage: File, identity: suspend (Long?, String?, Boolean) -> Unit,
                                       progress: suspend (Long, Long?) -> Unit): File =
                DirectTransfer(connections).fetch(task, stage, identity, progress)
        }, verifier, publisher) }

    @Synchronized fun submit(request: DirectRequest): String? {
        if (mutableSubmission.value.busy) return null
        val operation = UUID.randomUUID().toString()
        mutableSubmission.value = Submission(operation, busy = true)
        actions.launch {
            try {
                val task = repository.create(request)
                schedule(task.id)
                mutableSubmission.value = Submission(operation, accepted = task.id)
            } catch (error: Exception) {
                mutableSubmission.value = Submission(operation, error = DownloadEngine.classify(error).code)
            }
        }
        return operation
    }

    fun action(id: String, kind: String) {
        actions.launch {
            try {
                when (kind) { "cancel" -> cancel(id); "pause" -> pause(id); "resume" -> resume(id); "retry" -> retry(id) }
            } catch (_: Exception) { /* Room remains authoritative; no raw URL diagnostics. */ }
        }
    }

    suspend fun schedule(id: String, replace: Boolean = false) {
        val task = repository.get(id)
        val local = task.state in setOf(TransferState.VERIFYING, TransferState.PUBLISHING) || task.outputUri != null && task.digest != null
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("job_id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (local) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("download-$id",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request).result.get()
    }

    suspend fun cancel(id: String) {
        if (repository.requestCancel(id)) {
            val task = repository.get(id)
            if (task.state == TransferState.CANCELED) publisher.discardPending(task)
            WorkManager.getInstance(context).cancelUniqueWork("download-$id").result.get()
        }
    }

    suspend fun pause(id: String) {
        if (repository.requestPause(id)) WorkManager.getInstance(context).cancelUniqueWork("download-$id").result.get()
    }

    suspend fun resume(id: String) { repository.resume(id); schedule(id, replace = true) }
    suspend fun retry(id: String) { repository.retry(id); schedule(id, replace = true) }

    suspend fun recover() {
        repository.all().forEach { task ->
            if (task.state == TransferState.DOWNLOADING && engine.stage(task).length() > 0 && (!task.resumable || task.etag == null)) {
                repository.change(task.id) { it.copy(state = TransferState.FAILED, error = TransferError.INTERRUPTED) }
                return@forEach
            }
            if (task.state == TransferState.CANCEL_REQUESTED) {
                publisher.discardPending(task)
                repository.change(task.id) { it.copy(state = TransferState.CANCELED) }
                return@forEach
            }
            if (task.state == TransferState.PAUSING) {
                val bytes = engine.stage(task).length()
                repository.change(task.id) { it.copy(state = if (it.resumable && bytes > 0) TransferState.PAUSED else TransferState.FAILED,
                    bytes = bytes, error = if (it.resumable && bytes > 0) null else TransferError.RESUME_UNSUPPORTED) }
                return@forEach
            }
            if (task.state !in setOf(TransferState.COMPLETED, TransferState.CANCELED, TransferState.PAUSED, TransferState.FAILED))
                schedule(task.id)
        }
    }
}
