package com.thekain.video.transfer

import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
import com.thekain.video.data.*
import com.thekain.video.domain.*
import com.thekain.video.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

enum class EngineResult { COMPLETED, FAILED, RETRY, STOPPED }
private class RequestedStop : Exception()

/** One process-owned pipeline at a time. Workers and the Activity never own transfers. */
class DownloadEngine(
    private val repository: DownloadRepository,
    private val root: File,
    private val transfer: TransferAdapter,
    private val verifier: OutputVerifier,
    private val publisher: OutputPublisher
) {
    private val mutex = Mutex()
    fun stage(task: DownloadTask): File = File(root, "${task.id}/${task.attempt}/payload.part")

    suspend fun run(id: String, observed: suspend (DownloadTask) -> Unit = {}): EngineResult = mutex.withLock {
        withContext(Dispatchers.IO) { execute(id, observed) }
    }

    private suspend fun checkpoint(id: String) {
        currentCoroutineContext().ensureActive()
        if (repository.get(id).state in setOf(TransferState.CANCEL_REQUESTED, TransferState.PAUSING, TransferState.CANCELED))
            throw RequestedStop()
    }

    private suspend fun execute(id: String, observed: suspend (DownloadTask) -> Unit): EngineResult {
        suspend fun save(transform: (DownloadTask) -> DownloadTask): DownloadTask {
            val value = repository.change(id, transform)
            try { observed(value) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Notification/UI failures do not change verified media. */ }
            return value
        }
        var task = repository.get(id)
        if (task.state == TransferState.COMPLETED) return EngineResult.COMPLETED
        if (task.state in setOf(TransferState.CANCELED, TransferState.PAUSED)) return EngineResult.STOPPED
        if (task.state == TransferState.FAILED) return EngineResult.FAILED
        try {
            checkpoint(id)
            if (task.state == TransferState.RETRY_WAIT && task.eligibleAt > System.currentTimeMillis()) return EngineResult.RETRY
            val file = stage(task)
            if (task.state != TransferState.PUBLISHING && !(task.outputUri != null && task.digest != null && task.verifiedBytes != null)) {
                if (task.state != TransferState.VERIFYING) {
                    task = save { it.copy(state = TransferState.DOWNLOADING, error = null) }
                    transfer.fetch(task, file, { total, tag, resume ->
                        checkpoint(id)
                        save { it.copy(total = total, etag = tag, resumable = resume) }
                    }, { bytes, total ->
                        checkpoint(id)
                        save { it.copy(bytes = bytes, total = total) }
                    })
                    checkpoint(id)
                    task = save { it.copy(state = TransferState.VERIFYING) }
                }
                val media = verifier.verify(file, task.mime, task.total)
                checkpoint(id)
                task = save { it.copy(state = TransferState.PUBLISHING, verifiedBytes = media.bytes, digest = media.sha256) }
            } else if (task.state != TransferState.PUBLISHING) {
                task = save { it.copy(state = TransferState.PUBLISHING, error = null) }
            }
            val media = VerifiedMedia(task.verifiedBytes ?: throw TransferFailure(TransferError.VERIFY_FAILED),
                task.digest ?: throw TransferFailure(TransferError.VERIFY_FAILED))
            val uri = publisher.reserve(task)
            task = save { it.copy(outputUri = uri) }
            publisher.publish(task, file, uri, media)
            save { it.copy(state = TransferState.COMPLETED, error = null) }
            return EngineResult.COMPLETED
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { stopOrInterrupt(id) }
            throw cancelled
        } catch (_: RequestedStop) {
            stopOrInterrupt(id)
            return EngineResult.STOPPED
        } catch (failure: Exception) {
            val current = repository.get(id)
            if (current.state in setOf(TransferState.CANCEL_REQUESTED, TransferState.PAUSING, TransferState.CANCELED)) {
                stopOrInterrupt(id)
                return EngineResult.STOPPED
            }
            val typed = classify(failure)
            val retry = typed.retryable && current.state == TransferState.DOWNLOADING && current.failures < 2
            save { it.copy(state = if (retry) TransferState.RETRY_WAIT else TransferState.FAILED,
                failures = it.failures + 1, error = typed.code,
                eligibleAt = System.currentTimeMillis() + typed.retryAfterMillis) }
            return if (retry) EngineResult.RETRY else EngineResult.FAILED
        }
    }

    private suspend fun stopOrInterrupt(id: String) {
        val task = repository.get(id)
        when (task.state) {
            TransferState.CANCEL_REQUESTED -> {
                publisher.discardPending(task)
                repository.change(id) { it.copy(state = TransferState.CANCELED) }
            }
            TransferState.PAUSING -> {
                val bytes = stage(task).length()
                repository.change(id) { it.copy(state = if (it.resumable && bytes > 0) TransferState.PAUSED else TransferState.FAILED,
                    bytes = bytes, error = if (it.resumable && bytes > 0) null else TransferError.RESUME_UNSUPPORTED) }
            }
            TransferState.DOWNLOADING -> {
                if (!task.resumable || task.etag == null)
                    repository.change(id) { it.copy(state = TransferState.FAILED, error = TransferError.INTERRUPTED) }
            }
            else -> Unit // Keep VERIFYING/PUBLISHING intent for idempotent recovery.
        }
    }

    companion object {
        fun classify(error: Exception): TransferFailure {
            if (error is TransferFailure) return error
            if (error is SQLiteFullException || generateSequence<Throwable>(error) { it.cause }
                    .any { it is ErrnoException && it.errno == OsConstants.ENOSPC })
                return TransferFailure(TransferError.NO_DISK_SPACE)
            return when (error) {
                is SocketTimeoutException -> TransferFailure(TransferError.TIMEOUT, true)
                is java.io.FileNotFoundException -> TransferFailure(TransferError.VERIFY_FAILED)
                is SecurityException -> TransferFailure(TransferError.PERMISSION_DENIED)
                is IOException -> TransferFailure(TransferError.NETWORK_LOST, true)
                else -> TransferFailure(TransferError.UNKNOWN)
            }
        }
    }
}
