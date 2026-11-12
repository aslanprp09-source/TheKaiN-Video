package com.thekain.video

import com.thekain.video.source.*
import com.thekain.video.transfer.Connections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.ArrayDeque
import javax.net.ssl.HttpsURLConnection

class SourceAnalyzerTest {
    private class Reply(val code: Int = 200, val body: String = "", val headers: Map<String, String> = emptyMap()) : HttpsURLConnection(URL("https://example.invalid/page")) {
        var closed = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
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
}
