package com.thekain.video.transfer

import android.content.Context
import android.os.SystemClock
import com.thekain.runtime.lab.*
import com.thekain.video.data.DownloadTask
import com.thekain.video.domain.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext

/** Internal progressive-only transport. Staging is written by the coroutine, never a late IO callback. */
class IsolatedProgressiveTransfer(private val context: Context, private val policy: RemoteHttpsTransport.Policy,
    private val timeoutMillis: Long = 30_000, private val freeBytes: (File) -> Long = { it.usableSpace }) : TransferAdapter {
    @Volatile var lastEvidence: JSONObject? = null
        private set
    init { require(timeoutMillis in 100..30_000) }

    override suspend fun fetch(task: DownloadTask, stage: File, identity: suspend (Long?, String?, Boolean) -> Unit,
        progress: suspend (Long, Long?) -> Unit): File = withContext(Dispatchers.IO) {
        if (task.backend != TransferBackend.ISOLATED_OWNED || task.mime != "video/mp4")
            throw TransferFailure(TransferError.SOURCE_POLICY)
        if (stage.exists() && stage.length() > 0) throw TransferFailure(TransferError.INTERRUPTED)
        try { policy.validateCandidate(task.url) } catch (_: Exception) { throw TransferFailure(TransferError.SOURCE_POLICY) }
        stage.parentFile!!.mkdirs()
        val live = AtomicReference<RemoteHttpsTransport?>()
        // Cancellation always retires a partially acquired transport too. A late primitive owns no file.
        suspend fun <T> network(block: () -> T): T = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cause ->
                live.get()?.let { if (cause is TimeoutCancellationException) it.expireDownload() else it.close() }
            }
            Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable {
                try {
                    if (!continuation.isActive) return@Runnable
                    val value = block()
                    if (continuation.isActive) continuation.resumeWith(Result.success(value))
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
            })
        }
        var success = false
        var receipt: BrokerDownload? = null
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        try {
            withTimeout(timeoutMillis) {
                // Creation does not do DNS/TLS, wait for Binder, or load Python. Keeping assignment
                // in the coroutine prevents an untracked late transport after canceled acquisition.
                val transport = RemoteHttpsTransport.forDownloadUntil(context, policy, task.url, deadline)
                live.set(transport)
                currentCoroutineContext().ensureActive()
                val session = network { transport.openDownload() }
                try {
                    identity(null, null, false) // No range resume; partial crash recovery fails closed.
                    var bytes = 0L
                    progress(0, null)
                    FileOutputStream(stage, false).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = network { session.read(buffer) }
                            currentCoroutineContext().ensureActive()
                            if (count < 0) break
                            if (freeBytes(stage.parentFile!!) < count + 8L * 1024 * 1024)
                                throw TransferFailure(TransferError.NO_DISK_SPACE)
                            output.write(buffer, 0, count)
                            bytes += count
                            if (bytes > BrokerDownload.MAX_BYTES) throw TransferFailure(TransferError.VERIFY_FAILED)
                            progress(bytes, null)
                        }
                        output.fd.sync()
                    }
                    val result = network { session.receipt() }
                    if (result.error != 0) {
                        if (result.httpStatus != 0) {
                            // No early automatic retry around a server's Retry-After policy.
                            throw TransferFailure(TransferRules.httpError(result.httpStatus).code)
                        }
                        throw TransferFailure(map(result.error))
                    }
                    policy.validateCandidate(result.finalUrl)
                    if (result.finalUrl != task.url || result.contentType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT) !in
                        setOf("video/mp4", "application/octet-stream") || bytes != result.bytes)
                        throw TransferFailure(TransferError.VERIFY_FAILED)
                    identity(result.declaredBytes, null, false)
                    progress(bytes, result.declaredBytes)
                    receipt = result
                    success = true
                } finally { session.close() }
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            live.get()?.expireDownload()
            throw TransferFailure(TransferError.TIMEOUT)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: TransferFailure) { throw failure }
        catch (_: Exception) {
            throw TransferFailure(map(live.get()?.failure() ?: RuntimeResult.IPC_ERROR))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                live.get()?.let { transport ->
                    transport.close()
                    val until = SystemClock.elapsedRealtime() + 2600
                    while (!transport.terminated() && SystemClock.elapsedRealtime() < until) delay(10)
                    val evidence = transport.evidence().put("source_commit", com.thekain.video.BuildConfig.INTERNAL_RUNTIME_SOURCE)
                        .put("transfer_receipt", receipt != null).put("verified_media", false)
                    lastEvidence = evidence
                    val file = File(context.filesDir, "isolated-transfer-evidence.jsonl")
                    check(file.length() < 256 * 1024) { "INTERNAL_EVIDENCE_FULL" }
                    FileOutputStream(file, true).use { it.write((evidence.toString() + "\n").toByteArray(Charsets.UTF_8)) }
                    if (!transport.terminated() && success) throw TransferFailure(TransferError.ISOLATION_BLOCKED)
                }
            }
        }
        // DownloadEngine still verifies samples, tracks, frame decoding, SHA and the published URI.
        stage
    }
    private fun map(code: Int) = when (code) {
        RuntimeResult.TIMEOUT -> TransferError.TIMEOUT
        RuntimeResult.OUT_OF_SCOPE, RuntimeResult.TLS_ERROR, RuntimeResult.INVALID_REQUEST -> TransferError.SOURCE_POLICY
        RuntimeResult.RESPONSE_LIMIT, RuntimeResult.OUTPUT_LIMIT -> TransferError.RESPONSE_LIMIT
        RuntimeResult.MEMORY_LIMIT, RuntimeResult.CPU_LIMIT -> TransferError.RESOURCE_LIMIT
        RuntimeResult.CANCELED, RuntimeResult.WORKER_DIED -> TransferError.INTERRUPTED
        RuntimeResult.CLEANUP_FAILED, RuntimeResult.IPC_ERROR, RuntimeResult.BUSY, RuntimeResult.OK -> TransferError.ISOLATION_BLOCKED
        else -> TransferError.NETWORK_LOST
    }
}
