package com.thekain.video.source

import android.content.Context
import android.os.SystemClock
import com.thekain.runtime.lab.*
import com.thekain.video.domain.DirectLinks
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext

/** Explicit internal build only. Same SourceAnalyzer contract, actual isolated runtime, no raw Python/JSON API. */
class IsolatedSourceAnalyzer(
    context: Context,
    private val policy: RemoteHttpsTransport.Policy,
    private val timeoutMillis: Long = 30_000,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : SourceAnalyzer {
    private val context = context.applicationContext
    @Volatile var lastOutcome: IsolatedRuntime.Outcome? = null
        private set
    @Volatile var lastTransport: JSONObject? = null
        private set
    init { require(timeoutMillis in 100..30_000) }

    override suspend fun analyze(input: String): SourceMedia {
        val source = SourceUrls.parse(input)
        try { policy.validateCandidate(source.toString()) }
        catch (_: Exception) { throw AnalysisFailure(AnalysisError.UNSUPPORTED_SOURCE) }
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val operation = AtomicReference<IsolatedRuntime.Operation?>()
                    continuation.invokeOnCancellation { operation.get()?.cancel() }
                    io.dispatch(EmptyCoroutineContext, Runnable {
                        var runtime: IsolatedRuntime? = null
                        try {
                            if (!continuation.isActive) return@Runnable
                            val recovery = RemoteHttpsTransport.recover(context)
                            if (recovery !in 0..1) throw AnalysisFailure(if (recovery == 2) AnalysisError.BUSY else AnalysisError.ISOLATION_BLOCKED)
                            runtime = IsolatedRuntime.forSourceAnalysis(context, policy)
                            val recovered = runtime.recoverySignal().get(3, TimeUnit.SECONDS)
                            if (recovered.status !in 0..1) throw AnalysisFailure(if (recovered.status == 2) AnalysisError.BUSY else AnalysisError.ISOLATION_BLOCKED)
                            if (!continuation.isActive) return@Runnable
                            val remaining = deadline - SystemClock.elapsedRealtime()
                            if (remaining < 100) throw AnalysisFailure(AnalysisError.TIMEOUT)
                            val op = runtime.analyzeSource(source.toString(), remaining)
                            operation.set(op)
                            if (!continuation.isActive) op.cancel()
                            // Authoritative signal: the UI timeout/cancel remains independent of cleanup delivery.
                            val out = op.outcomeSignal().get((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 3000, TimeUnit.MILLISECONDS)
                            val transport = out.transportEvidence() ?: JSONObject().put("not_created", true)
                            record(out, transport)
                            lastTransport = transport
                            lastOutcome = out
                            if (out.result.error != RuntimeResult.OK) throw AnalysisFailure(map(out.result.error))
                            if (transport.optBoolean("not_created")) throw AnalysisFailure(AnalysisError.ISOLATION_BLOCKED)
                            if (!out.deathConfirmed || !out.ownerCanaryIntact || out.workerUid == android.os.Process.myUid()
                                || out.childProtection != 255 || out.observerPythonLoaded || out.result.pythonCalls != 1L)
                                throw AnalysisFailure(AnalysisError.ISOLATION_BLOCKED)
                            val metadata = out.result.analysis ?: throw AnalysisFailure(AnalysisError.NO_FORMATS)
                            val variants = metadata.formats().map { format ->
                                policy.validateCandidate(format.url)
                                val request = try { DirectLinks.parse(format.url) }
                                    catch (_: Exception) { throw AnalysisFailure(AnalysisError.UNSUPPORTED_SOURCE) }
                                MediaVariant(format.id, request, format.bytes.takeIf { it > 0 }, downloadAllowed = false,
                                    width = format.width.takeIf { it > 0 }, height = format.height.takeIf { it > 0 }, fps = format.fps.takeIf { it > 0 },
                                    videoCodec = format.videoCodec, audioCodec = format.audioCodec)
                            }
                            if (SystemClock.elapsedRealtime() >= deadline) throw AnalysisFailure(AnalysisError.TIMEOUT)
                            if (continuation.isActive) continuation.resumeWith(Result.success(SourceMedia(metadata.title, metadata.host, variants)))
                        } catch (error: Exception) {
                            val safe = if (error is AnalysisFailure) error else if (error is java.util.concurrent.TimeoutException)
                                AnalysisFailure(AnalysisError.ISOLATION_BLOCKED) else AnalysisFailure(AnalysisError.UNKNOWN)
                            if (continuation.isActive) continuation.resumeWith(Result.failure(safe))
                        } finally { runtime?.close() }
                    })
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw AnalysisFailure(AnalysisError.TIMEOUT)
        }
    }
    private fun map(code: Int) = when (code) {
        RuntimeResult.TIMEOUT -> AnalysisError.TIMEOUT
        RuntimeResult.BUSY -> AnalysisError.BUSY
        RuntimeResult.MEMORY_LIMIT, RuntimeResult.CPU_LIMIT -> AnalysisError.RESOURCE_LIMIT
        RuntimeResult.CLEANUP_FAILED -> AnalysisError.ISOLATION_BLOCKED
        RuntimeResult.INVALID_REQUEST -> AnalysisError.INVALID_URL
        RuntimeResult.RESPONSE_LIMIT, RuntimeResult.OUTPUT_LIMIT -> AnalysisError.TOO_LARGE
        RuntimeResult.OUT_OF_SCOPE, RuntimeResult.NATIVE_FAILED -> AnalysisError.UNSUPPORTED_SOURCE
        else -> AnalysisError.NETWORK
    }
    private fun record(out: IsolatedRuntime.Outcome, transport: JSONObject) {
        val data = JSONObject().put("id", out.result.id).put("source_commit", com.thekain.video.BuildConfig.INTERNAL_RUNTIME_SOURCE)
            .put("error", out.result.error).put("formats", out.result.formats).put("python_calls", out.result.pythonCalls)
            .put("download_enabled", false).put("death_confirmed", out.deathConfirmed).put("canary_intact", out.ownerCanaryIntact)
            .put("worker_pid", out.workerPid).put("worker_uid", out.workerUid).put("owner_uid", android.os.Process.myUid())
            .put("observer_python_loaded", out.observerPythonLoaded).put("child_protection", out.childProtection)
            .put("peak_rss_bytes", out.peakRssBytes).put("observed_cpu_ms", out.observedCpuMs).put("elapsed_ms", out.elapsedMs)
            .put("machine", out.result.machine).put("pages", out.result.pages).put("network", transport).put("production_ready", false)
        val file = java.io.File(context.filesDir, "isolated-source-evidence.jsonl")
        check(file.length() < 256 * 1024) { "INTERNAL_EVIDENCE_FULL" } // Preserve previous failures; never truncate them on retry.
        java.io.FileOutputStream(file, true).use { it.write((data.toString() + "\n").toByteArray(Charsets.UTF_8)) }
    }
}
