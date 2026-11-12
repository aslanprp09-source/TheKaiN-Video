package com.thekain.video

import com.thekain.video.source.*
import com.thekain.video.transfer.Connections
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.io.InputStream
import javax.net.ssl.HttpsURLConnection

@OptIn(ExperimentalCoroutinesApi::class)
class SourceAnalyzerTest {
    private open class Reply(val code: Int = 200, val body: String = "", val headers: Map<String, String> = emptyMap()) : HttpsURLConnection(URL("https://example.invalid/page")) {
        @Volatile var closed = false
        override fun getResponseCode() = code
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getHeaderField(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getContentType() = getHeaderField("Content-Type")
        override fun getContentLengthLong() = getHeaderField("Content-Length")?.toLong() ?: -1
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "owned-fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private fun analyzer(vararg responses: Reply): Pair<HttpsSourceAnalyzer, MutableList<URL>> {
        val queue = ArrayDeque(responses.toList())
        val calls = mutableListOf<URL>()
        return HttpsSourceAnalyzer(Connections { url -> calls += url; queue.removeFirst() }) to calls
    }
    private fun html(value: String) = Reply(body = value, headers = mapOf("Content-Type" to "text/html; charset=utf-8"))
    private fun media(mime: String, size: String? = null) = Reply(headers = mapOf("Content-Type" to mime) + (size?.let { mapOf("Content-Length" to it) } ?: emptyMap()))

    @Test fun directFileExposesOneRealFormatAndUnknownSizeStaysUnknown() = runBlocking<Unit> {
        val (analyzer, calls) = analyzer(media("video/mp4"))
        val result = analyzer.analyze("https://example.invalid/owned.mp4?token=owned")
        assertEquals(1, result.variants.size)
        assertEquals("MP4", result.variants.single().extension)
        assertNull(result.variants.single().bytes)
        assertEquals(1, calls.size)
        assertFalse(result.toString().contains("token"))
        assertFalse(result.variants.single().toString().contains("token"))
    }

    @Test fun ownedHtml5PageResolvesActualSourcesAndAudioWithoutQualityFabrication() = runBlocking<Unit> {
        val (analyzer, calls) = analyzer(html("<title>Owned &amp; permitted</title><video><source src='clip.mp4'><source src='clip.webm'><source src='adaptive.m3u8'></video><audio src='sound.m4a'></audio>"),
            media("video/mp4", "123"), media("video/webm", "456"), media("audio/mp4"))
        val result = analyzer.analyze("https://example.invalid/owned-page")
        assertEquals("Owned & permitted", result.title)
        assertEquals(listOf("MP4", "WEBM", "M4A"), result.variants.map { it.extension })
        assertEquals(listOf(123L, 456L, null), result.variants.map { it.bytes })
        assertTrue(result.variants.last().audioOnly)
        assertEquals(4, calls.size)
    }

    @Test fun pageNeverExecutesScriptsOrFetchesTrackingPosterOrUnsafeSchemes() = runBlocking<Unit> {
        val (analyzer, calls) = analyzer(html("<video poster='https://tracker.invalid/pixel.png' src='good.mp4'></video><video src='http://example.invalid/insecure.mp4'></video><video src='file:///private.mp4'></video><script src='https://tracker.invalid/execute.js'></script>"), media("video/mp4"))
        val result = analyzer.analyze("https://example.invalid/page")
        assertEquals(1, result.variants.size)
        assertNull(result.poster)
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.host == "example.invalid" })
    }

    @Test fun scriptsOnlyAndUnsupportedManifestsDoNotProduceFictionalFormats() = runBlocking<Unit> {
        val (analyzer, calls) = analyzer(html("<video src='blob:owned'></video><script>var video='clip.mp4';</script><audio src='adaptive.mpd'></audio>"))
        try { analyzer.analyze("https://example.invalid/script-player"); fail() }
        catch (error: AnalysisFailure) { assertEquals(AnalysisError.UNSUPPORTED_SOURCE, error.code) }
        assertEquals(1, calls.size)
    }

    @Test fun fallbackHeadUsesActualContentRangeTotalRatherThanOneByte() = runBlocking<Unit> {
        val noHead = Reply(405)
        val range = Reply(206, "x", mapOf("Content-Type" to "video/mp4", "Content-Length" to "1", "Content-Range" to "bytes 0-0/321"))
        val (analyzer, _) = analyzer(noHead, range)
        assertEquals(321L, analyzer.analyze("https://example.invalid/clip.mp4").variants.single().bytes)
        assertEquals("bytes=0-0", range.getRequestProperty("Range"))
        assertTrue(noHead.closed && range.closed)
    }

    @Test fun failedResponsesAndWrongMimeAreActionable() = runBlocking<Unit> {
        for ((reply, expected) in listOf(Reply(403) to AnalysisError.FORBIDDEN, Reply(404) to AnalysisError.MISSING,
            Reply(429) to AnalysisError.RATE_LIMITED, media("text/html") to AnalysisError.NO_FORMATS)) {
            val (analyzer, _) = analyzer(reply)
            try { analyzer.analyze("https://example.invalid/clip.mp4"); fail() }
            catch (error: AnalysisFailure) { assertEquals(expected, error.code) }
            assertTrue(reply.closed)
        }
    }

    @Test fun boundedPagesAndUnsafeRedirectsAreRejected() = runBlocking<Unit> {
        val huge = Reply(body = " ".repeat(512 * 1024 + 1), headers = mapOf("Content-Type" to "text/html"))
        val redirect = Reply(302, headers = mapOf("Location" to "http://example.invalid/clip.mp4"))
        for ((reply, expected) in listOf(huge to AnalysisError.TOO_LARGE, redirect to AnalysisError.INVALID_URL)) {
            val (analyzer, _) = analyzer(reply)
            try { analyzer.analyze("https://example.invalid/page"); fail() }
            catch (error: AnalysisFailure) { assertEquals(expected, error.code) }
            assertTrue(reply.closed)
        }
    }

    @Test fun tricklePageHitsOneDeadlineLongBeforeTheByteLimit() = runTest {
        val elapsed = AtomicLong()
        var read = 0
        val slow = object : Reply(headers = mapOf("Content-Type" to "text/html")) {
            override fun getInputStream() = object : InputStream() {
                override fun read(): Int { elapsed.addAndGet(400_000_000); read++; return ' '.code }
                override fun read(b: ByteArray, off: Int, len: Int): Int { b[off] = read().toByte(); return 1 }
            }
        }
        val analyzer = HttpsSourceAnalyzer(Connections { slow }, clockNanos = elapsed::get,
            ioDispatcher = StandardTestDispatcher(testScheduler))
        val result = async { runCatching { analyzer.analyze("https://example.invalid/slow-page") } }
        runCurrent()
        assertEquals(AnalysisError.TIMEOUT, (result.await().exceptionOrNull() as AnalysisFailure).code)
        assertEquals(30_000_000_000L, elapsed.get())
        assertEquals(75, read) // Only 75 bytes; a byte cap or 5s idle timeout cannot solve this.
        assertTrue(slow.closed)
    }

    @Test fun pageAndSequentialSourcesShareTheSameBudget() = runTest {
        val elapsed = AtomicLong()
        val replies = mutableListOf<Reply>()
        val page = object : Reply(body = "<video>" + (1..8).joinToString("") { "<source src='clip$it.mp4'>" } + "</video>",
            headers = mapOf("Content-Type" to "text/html")) {
            override fun getResponseCode(): Int { elapsed.addAndGet(3_000_000_000); return 200 }
        }
        replies += page
        repeat(8) {
            replies += object : Reply(headers = mapOf("Content-Type" to "video/mp4")) {
                private var resolved = false
                override fun getResponseCode(): Int {
                    if (resolved) return 200
                    val allowed = minOf(3500, readTimeout)
                    elapsed.addAndGet(allowed * 1_000_000L)
                    if (allowed < 3500) throw java.net.SocketTimeoutException()
                    resolved = true
                    return 200
                }
            }
        }
        var calls = 0
        val analyzer = HttpsSourceAnalyzer(Connections { replies[calls++] }, clockNanos = elapsed::get,
            ioDispatcher = StandardTestDispatcher(testScheduler))
        val result = async { runCatching { analyzer.analyze("https://example.invalid/owned-page") } }
        runCurrent()
        assertEquals(AnalysisError.TIMEOUT, (result.await().exceptionOrNull() as AnalysisFailure).code)
        assertEquals(30_000_000_000L, elapsed.get())
        assertEquals(9, calls)
        assertEquals(2500, replies.last().readTimeout) // Last source does not receive another 5 seconds.
        assertTrue(replies.all { it.closed })
    }

    private class BlockedReply : Reply(headers = mapOf("Content-Type" to "text/html")) {
        val reading = CountDownLatch(1)
        val released = CountDownLatch(1)
        val finished = CountDownLatch(1)
        override fun getInputStream() = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                try { check(released.await(5, TimeUnit.SECONDS)); return -1 }
                finally { finished.countDown() }
            }
            override fun read(b: ByteArray, off: Int, len: Int) = read()
        }
        override fun disconnect() { super.disconnect(); released.countDown() }
    }

    @Test fun scheduledDeadlineClosesAnActivelyBlockedRead() = runTest {
        val response = BlockedReply()
        val analyzer = HttpsSourceAnalyzer(Connections { response })
        val result = async { runCatching { analyzer.analyze("https://example.invalid/blocked") } }
        runCurrent()
        assertTrue(response.reading.await(5, TimeUnit.SECONDS))
        advanceTimeBy(30_001); runCurrent()
        assertEquals(AnalysisError.TIMEOUT, (result.await().exceptionOrNull() as AnalysisFailure).code)
        assertTrue(response.closed)
        assertTrue(response.finished.await(5, TimeUnit.SECONDS))
    }

    @Test fun userCancellationClosesTheConnectionAndRemainsCancellation() = runTest {
        val response = BlockedReply()
        val analyzer = HttpsSourceAnalyzer(Connections { response })
        val result = async { analyzer.analyze("https://example.invalid/blocked") }
        runCurrent()
        assertTrue(response.reading.await(5, TimeUnit.SECONDS))
        result.cancel(); runCurrent(); result.join()
        assertTrue(result.isCancelled)
        assertTrue(response.closed)
        assertTrue(response.finished.await(5, TimeUnit.SECONDS))
    }

    @Test fun optionalPosterCannotSwallowTheOverallDeadline() = runTest {
        val elapsed = AtomicLong()
        val page = html("<video src='owned.mp4' poster='poster.png'></video>")
        val file = media("video/mp4")
        val poster = object : Reply(headers = mapOf("Content-Type" to "image/png")) {
            override fun getInputStream() = object : InputStream() {
                override fun read(): Int { elapsed.addAndGet(400_000_000); return 0 }
                override fun read(b: ByteArray, off: Int, len: Int): Int { b[off] = read().toByte(); return 1 }
            }
        }
        val replies = ArrayDeque(listOf(page, file, poster))
        val analyzer = HttpsSourceAnalyzer(Connections { replies.removeFirst() }, clockNanos = elapsed::get,
            ioDispatcher = StandardTestDispatcher(testScheduler))
        val result = async { runCatching { analyzer.analyze("https://example.invalid/page") } }
        runCurrent()
        assertEquals(AnalysisError.TIMEOUT, (result.await().exceptionOrNull() as AnalysisFailure).code)
        assertTrue(page.closed && file.closed && poster.closed)
    }

    @Test fun safeHttpsBaseResolvesCdnSourcesWithoutCredentials() = runBlocking<Unit> {
        val response = media("video/mp4")
        val (analyzer, calls) = analyzer(html("<base href='https://cdn.example.invalid/files/'><video src='owned.mp4' poster='poster.png'></video>"), response)
        val result = analyzer.analyze("https://example.invalid/page")
        assertEquals("https://cdn.example.invalid/files/owned.mp4", calls.last().toString())
        assertEquals("https://cdn.example.invalid/files/owned.mp4", result.variants.single().request.url)
        assertNull(result.poster) // Base does not weaken the page-origin restriction on thumbnails.
        assertNull(response.getRequestProperty("Cookie"))
        assertNull(response.getRequestProperty("Authorization"))
        val (unsafe, unsafeCalls) = this@SourceAnalyzerTest.analyzer(html("<base href='http://example.invalid/'><video src='owned.mp4'></video>"))
        try { unsafe.analyze("https://example.invalid/page"); fail() }
        catch (error: AnalysisFailure) { assertEquals(AnalysisError.INVALID_URL, error.code) }
        assertEquals(1, unsafeCalls.size)
    }
}
