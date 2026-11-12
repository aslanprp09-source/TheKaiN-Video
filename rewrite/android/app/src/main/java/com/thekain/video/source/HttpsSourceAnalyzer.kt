package com.thekain.video.source

import com.thekain.video.transfer.Connections
import com.thekain.video.transfer.systemConnections
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.SocketTimeoutException
import javax.net.ssl.HttpsURLConnection

/** Bounded static HTML5 discovery. No JavaScript, cookies, accounts, or remote executable updates. */
class HttpsSourceAnalyzer(private val connections: Connections = systemConnections) : SourceAnalyzer {
    override suspend fun analyze(input: String): SourceMedia = withContext(Dispatchers.IO) {
        val source = SourceUrls.parse(input)
        try {
            SourceUrls.direct(source)?.let { request ->
                val bytes = metadata(source, request.mime)
                return@withContext SourceMedia(request.name, source.host, listOf(MediaVariant("original", request, bytes)))
            }
            val page = open(source, "GET")
            val finalUri: URI
            val body: ByteArray
            try {
                if (!page.contentType.orEmpty().substringBefore(';').trim().equals("text/html", true))
                    throw AnalysisFailure(AnalysisError.UNSUPPORTED_SOURCE)
                finalUri = page.url.toURI()
                body = read(page, 512 * 1024)
            } finally { page.disconnect() }
            currentCoroutineContext().ensureActive()
            val document = Jsoup.parse(String(body, Charsets.UTF_8), finalUri.toString())
            val candidates = document.select("video[src], video source[src], audio[src], audio source[src]")
                .mapNotNull { element ->
                    try { SourceUrls.parse(finalUri.resolve(element.attr("src")).toString()) }
                    catch (_: Exception) { null }
                }.distinct().take(8)
            var failedSource: AnalysisFailure? = null
            val variants = candidates.mapNotNull { uri ->
                val request = SourceUrls.direct(uri) ?: return@mapNotNull null
                currentCoroutineContext().ensureActive()
                try { MediaVariant("source-${candidates.indexOf(uri)}", request, metadata(uri, request.mime)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: AnalysisFailure) { failedSource = failure; null } // Never fabricate broken choices.
            }
            if (variants.isEmpty()) throw failedSource ?: AnalysisFailure(AnalysisError.UNSUPPORTED_SOURCE)
            val poster = document.selectFirst("video[poster]")?.attr("poster")?.let { path ->
                try {
                    val uri = SourceUrls.parse(finalUri.resolve(path).toString())
                    // A page cannot cause third-party thumbnail tracking requests.
                    if (uri.host != finalUri.host || uri.port != finalUri.port) null else {
                        val image = open(uri, "GET", sameOrigin = finalUri)
                        try {
                            if (!image.contentType.orEmpty().startsWith("image/")) null else read(image, 1024 * 1024)
                        } finally { image.disconnect() }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            SourceMedia(document.title().trim().take(320).ifBlank { variants.first().request.name }, finalUri.host, variants, poster)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: AnalysisFailure) { throw failure }
        catch (_: SocketTimeoutException) { throw AnalysisFailure(AnalysisError.TIMEOUT) }
        catch (_: java.io.IOException) { throw AnalysisFailure(AnalysisError.NETWORK) }
        catch (_: Exception) { throw AnalysisFailure(AnalysisError.UNKNOWN) }
    }

    private suspend fun metadata(uri: URI, mime: String): Long? {
        var response = open(uri, "HEAD", allowMethodFallback = true)
        try {
            if (response.responseCode in setOf(405, 501)) {
                response.disconnect()
                response = open(uri, "GET", rangeProbe = true)
            }
            val type = response.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (type != null && type !in setOf(mime, "application/octet-stream", "binary/octet-stream"))
                throw AnalysisFailure(AnalysisError.NO_FORMATS)
            return if (response.responseCode == 206) {
                Regex("bytes 0-0/(\\d+)").matchEntire(response.getHeaderField("Content-Range").orEmpty())
                    ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
            } else response.contentLengthLong.takeIf { it > 0 }
        } finally { response.disconnect() }
    }

    private suspend fun open(initial: URI, method: String, allowMethodFallback: Boolean = false,
                             rangeProbe: Boolean = false, sameOrigin: URI? = null): HttpsURLConnection {
        var uri = initial
        repeat(6) {
            currentCoroutineContext().ensureActive()
            SourceUrls.parse(uri.toString())
            if (sameOrigin != null && (uri.host != sameOrigin.host || uri.port != sameOrigin.port))
                throw AnalysisFailure(AnalysisError.UNSUPPORTED_SOURCE)
            val response = connections.open(uri.toURL()).apply {
                instanceFollowRedirects = false
                requestMethod = method
                connectTimeout = 8000
                readTimeout = 5000
                setRequestProperty("Accept-Encoding", "identity")
                if (rangeProbe) setRequestProperty("Range", "bytes=0-0")
            }
            val status = try { response.responseCode } catch (error: Exception) { response.disconnect(); throw error }
            if (status in setOf(301, 302, 303, 307, 308)) {
                val location = response.getHeaderField("Location")
                response.disconnect()
                if (location.isNullOrBlank()) throw AnalysisFailure(AnalysisError.UNSUPPORTED_SOURCE)
                uri = uri.resolve(location)
            } else {
                if (status !in 200..299 && !(allowMethodFallback && status in setOf(405, 501))) {
                    response.disconnect()
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

    private suspend fun read(response: HttpsURLConnection, limit: Int): ByteArray {
        if (response.contentLengthLong > limit) throw AnalysisFailure(AnalysisError.TOO_LARGE)
        return response.inputStream.use { input ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (result.size() + count > limit) throw AnalysisFailure(AnalysisError.TOO_LARGE)
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
    }
}
