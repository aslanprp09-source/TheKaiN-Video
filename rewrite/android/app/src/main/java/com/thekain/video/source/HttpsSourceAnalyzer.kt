package com.thekain.video.source

import com.thekain.video.transfer.Connections
import com.thekain.video.transfer.systemConnections
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import javax.net.ssl.HttpsURLConnection

/** Bounded static HTML5 discovery. No JavaScript, cookies, accounts, or remote executable updates. */
class HttpsSourceAnalyzer(
    private val connections: Connections = systemConnections,
    private val timeoutMillis: Long = 30_000,
    private val clockNanos: () -> Long = System::nanoTime,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SourceAnalyzer {
    init { require(timeoutMillis in 1..30_000) }

    override suspend fun analyze(input: String): SourceMedia = try {
        withTimeout(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val analysis = Analysis(continuation)
                continuation.invokeOnCancellation { analysis.close() }
                // A cancellable bridge owns the blocking work. withContext(IO) alone would wait
                // for a blocking child read before returning TIMEOUT. Closing the registered
                // connection aborts the read; no detached result can update the caller afterwards.
                ioDispatcher.dispatch(EmptyCoroutineContext, Runnable {
                    try {
                        analysis.check()
                        val result = analysis.execute(input)
                        analysis.check()
                        if (continuation.isActive) continuation.resumeWith(Result.success(result))
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                    } finally { analysis.close() }
                })
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive() // Preserve a parent timeout/cancellation.
        throw AnalysisFailure(AnalysisError.TIMEOUT)
    }

    private inner class Analysis(private val continuation: CancellableContinuation<SourceMedia>) {
        private val started = clockNanos()
        private val active = AtomicReference<HttpsURLConnection?>()
        fun check() {
            if (!continuation.isActive) throw CancellationException("Analysis canceled")
            if ((clockNanos() - started) / 1_000_000 >= timeoutMillis) throw AnalysisFailure(AnalysisError.TIMEOUT)
        }
        private fun remainingMillis(): Int {
            check()
            return (timeoutMillis - (clockNanos() - started) / 1_000_000).coerceIn(1, timeoutMillis).toInt()
        }
        fun close() { active.getAndSet(null)?.let { runCatching { it.disconnect() } } }
        private fun release(connection: HttpsURLConnection) {
            if (active.compareAndSet(connection, null)) runCatching { connection.disconnect() }
        }
        private fun register(connection: HttpsURLConnection) {
            active.set(connection)
            // Cancellation may have arrived immediately before registration.
            try { check() } catch (error: Exception) { release(connection); throw error }
        }

        fun execute(input: String): SourceMedia {
            val source = SourceUrls.parse(input)
            try {
                SourceUrls.direct(source)?.let { request ->
                    val bytes = metadata(source, request.mime)
                    return SourceMedia(request.name, source.host, listOf(MediaVariant("original", request, bytes)))
                }
                val page = open(source, "GET")
                val finalUri: URI
                val body: ByteArray
                try {
                    if (!page.contentType.orEmpty().substringBefore(';').trim().equals("text/html", true))
                        throw AnalysisFailure(AnalysisError.UNSUPPORTED_SOURCE)
                    finalUri = page.url.toURI()
                    body = read(page, 512 * 1024)
                } finally { release(page) }
                check()
                val document = Jsoup.parse(String(body, Charsets.UTF_8), finalUri.toString())
                check()
                val base = document.selectFirst("base[href]")?.attr("href")?.takeIf { it.isNotBlank() }
                    ?.let { SourceUrls.parse(finalUri.resolve(it).toString()) } ?: finalUri
                val candidates = document.select("video[src], video source[src], audio[src], audio source[src]")
                    .mapNotNull { element ->
                        try { SourceUrls.parse(base.resolve(element.attr("src")).toString()) }
                        catch (_: Exception) { null }
                    }.distinct().take(8)
                var failedSource: AnalysisFailure? = null
                val variants = candidates.mapNotNull { uri ->
                    val request = SourceUrls.direct(uri) ?: return@mapNotNull null
                    check()
                    try { MediaVariant("source-${candidates.indexOf(uri)}", request, metadata(uri, request.mime)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: AnalysisFailure) { if (failure.code == AnalysisError.TIMEOUT) throw failure; failedSource = failure; null } // Never fabricate broken choices.
                }
                if (variants.isEmpty()) throw failedSource ?: AnalysisFailure(AnalysisError.UNSUPPORTED_SOURCE)
                val poster = document.selectFirst("video[poster]")?.attr("poster")?.let { path ->
                    try {
                        val uri = SourceUrls.parse(base.resolve(path).toString())
                        // A page cannot cause third-party thumbnail tracking requests.
                        if (uri.host != finalUri.host || uri.port != finalUri.port) null else {
                            val image = open(uri, "GET", sameOrigin = finalUri)
                            try {
                                if (!image.contentType.orEmpty().startsWith("image/")) null else read(image, 1024 * 1024)
                            } finally { release(image) }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: AnalysisFailure) { if (failure.code == AnalysisError.TIMEOUT) throw failure; null }
                    catch (_: Exception) { check(); null }
                }
                check()
                return SourceMedia(document.title().trim().take(320).ifBlank { variants.first().request.name }, finalUri.host, variants, poster)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: AnalysisFailure) { throw failure }
            catch (_: SocketTimeoutException) { throw AnalysisFailure(AnalysisError.TIMEOUT) }
            catch (_: java.io.IOException) { check(); throw AnalysisFailure(AnalysisError.NETWORK) }
            catch (_: Exception) { throw AnalysisFailure(AnalysisError.UNKNOWN) }
        }

        private fun metadata(uri: URI, mime: String): Long? {
            var response = open(uri, "HEAD", allowMethodFallback = true)
            try {
                if (response.responseCode in setOf(405, 501)) {
                    release(response)
                    response = open(uri, "GET", rangeProbe = true)
                }
                val type = response.contentType?.substringBefore(';')?.trim()?.lowercase()
                if (type != null && type !in setOf(mime, "application/octet-stream", "binary/octet-stream"))
                    throw AnalysisFailure(AnalysisError.NO_FORMATS)
                return if (response.responseCode == 206) {
                    Regex("bytes 0-0/(\\d+)").matchEntire(response.getHeaderField("Content-Range").orEmpty())
                        ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
                } else response.contentLengthLong.takeIf { it > 0 }
            } finally { release(response) }
        }

        private fun open(initial: URI, method: String, allowMethodFallback: Boolean = false,
                                 rangeProbe: Boolean = false, sameOrigin: URI? = null): HttpsURLConnection {
            var uri = initial
            repeat(6) {
                check()
                SourceUrls.parse(uri.toString())
                if (sameOrigin != null && (uri.host != sameOrigin.host || uri.port != sameOrigin.port))
                    throw AnalysisFailure(AnalysisError.UNSUPPORTED_SOURCE)
                val response = connections.open(uri.toURL())
                register(response)
                val status = try {
                    response.apply {
                        instanceFollowRedirects = false
                        requestMethod = method
                        connectTimeout = minOf(8000, remainingMillis())
                        readTimeout = minOf(5000, remainingMillis())
                        setRequestProperty("Accept-Encoding", "identity")
                        if (rangeProbe) setRequestProperty("Range", "bytes=0-0")
                    }
                    response.responseCode.also { check() }
                } catch (error: Exception) { release(response); throw error }
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = response.getHeaderField("Location")
                    release(response)
                    if (location.isNullOrBlank()) throw AnalysisFailure(AnalysisError.UNSUPPORTED_SOURCE)
                    uri = uri.resolve(location)
                } else {
                    if (status !in 200..299 && !(allowMethodFallback && status in setOf(405, 501))) {
                        release(response)
                        throw AnalysisFailure(when (status) {
                            401, 403 -> AnalysisError.FORBIDDEN
                            404, 410 -> AnalysisError.MISSING
                            429 -> AnalysisError.RATE_LIMITED
                            else -> AnalysisError.NETWORK
                        })
                    }
                    return response
                }
            }
            throw AnalysisFailure(AnalysisError.UNSUPPORTED_SOURCE)
        }

        private fun read(response: HttpsURLConnection, limit: Int): ByteArray {
            if (response.contentLengthLong > limit) throw AnalysisFailure(AnalysisError.TOO_LARGE)
            return response.inputStream.use { input ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    check()
                    val count = input.read(buffer)
                    check()
                    if (count < 0) break
                    if (result.size() + count > limit) throw AnalysisFailure(AnalysisError.TOO_LARGE)
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
        }
    }
}
