package com.thekain.video

import com.thekain.video.data.DownloadTask
import com.thekain.video.domain.*
import com.thekain.video.transfer.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class DirectTransferTest {
    private class Response(val status: Int, val bytes: ByteArray, val headers: Map<String, String>) : HttpsURLConnection(URL("https://example.invalid/owned.mp4")) {
        var closed = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getHeaderField(name: String) = headers[name]
        override fun getContentLengthLong() = headers["Content-Length"]?.toLong() ?: -1
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    private fun task() = DownloadTask("fixture", "https://example.invalid/owned.mp4", "owned.mp4", "video/mp4")
    private fun file(): File = File.createTempFile("m4-fixture", ".part").apply { delete() }
    private val body = "owned-fixture".toByteArray()

    @Test fun directSuccessPersistsMeasuredBytesButNotCompletion() = runBlocking {
        val response = Response(200, body, mapOf("Content-Length" to body.size.toString()))
        val path = file()
        try {
            val events = mutableListOf<Pair<Long, Long?>>()
            var resumable = true
            DirectTransfer(Connections { response }).fetch(task(), path, { _, _, value -> resumable = value }, { bytes, total -> events += bytes to total })
            assertArrayEquals(body, path.readBytes())
            assertEquals(body.size.toLong() to body.size.toLong(), events.last())
            assertFalse(resumable) // A 200 response is not Range proof.
            assertTrue(response.closed)
        } finally { path.delete() }
    }

    @Test fun unknownLengthRemainsNullUntilVerification() = runBlocking {
        val response = Response(200, body, emptyMap())
        val path = file()
        try {
            val totals = mutableListOf<Long?>()
            DirectTransfer(Connections { response }).fetch(task(), path, { total, _, _ -> totals += total }, { _, total -> totals += total })
            assertTrue(totals.all { it == null })
            assertArrayEquals(body, path.readBytes())
        } finally { path.delete() }
    }

    @Test fun simulatedNoSpaceFailsBeforeWriting() = runBlocking {
        val response = Response(200, body, mapOf("Content-Length" to body.size.toString()))
        val path = file()
        try {
            try { DirectTransfer(Connections { response }, freeBytes = { 0 }).fetch(task(), path, { _, _, _ -> }, { _, _ -> }); fail() }
            catch (error: TransferFailure) { assertEquals(TransferError.NO_DISK_SPACE, error.code) }
            assertFalse(path.exists())
            assertTrue(response.closed)
        } finally { path.delete() }
    }

    @Test fun ignoredRangeNeverAppendsToPartial() = runBlocking {
        val response = Response(200, body, mapOf("ETag" to "\"owned\""))
        val path = file().apply { writeBytes(body.take(3).toByteArray()) }
        try {
            val paused = task().copy(bytes = 3, total = body.size.toLong(), resumable = true, etag = "\"owned\"")
            try { DirectTransfer(Connections { response }).fetch(paused, path, { _, _, _ -> }, { _, _ -> }); fail() }
            catch (error: TransferFailure) { assertEquals(TransferError.RESUME_UNSUPPORTED, error.code) }
            assertEquals(3L, path.length())
        } finally { path.delete() }
    }

    @Test fun matching206ResumesWithStrongValidator() = runBlocking {
        val response = Response(206, body.drop(3).toByteArray(), mapOf("ETag" to "\"owned\"", "Content-Range" to "bytes 3-${body.size - 1}/${body.size}"))
        val path = file().apply { writeBytes(body.take(3).toByteArray()) }
        try {
            val paused = task().copy(bytes = 3, total = body.size.toLong(), resumable = true, etag = "\"owned\"")
            DirectTransfer(Connections { response }).fetch(paused, path, { _, _, _ -> }, { _, _ -> })
            assertArrayEquals(body, path.readBytes())
            assertEquals("bytes=3-", response.getRequestProperty("Range"))
            assertEquals("\"owned\"", response.getRequestProperty("If-Range"))
        } finally { path.delete() }
    }

    @Test fun cancelClosesConnectionWithoutReturningSuccess() = runBlocking {
        val response = Response(200, body, emptyMap())
        val path = file()
        try {
            try {
                DirectTransfer(Connections { response }).fetch(task(), path, { _, _, _ -> }, { bytes, _ -> if (bytes > 0) throw CancellationException("owned cancel") })
                fail()
            } catch (_: CancellationException) { assertTrue(response.closed) }
        } finally { path.delete() }
    }
}
