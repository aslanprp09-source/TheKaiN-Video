package com.thekain.video

import com.thekain.video.data.DownloadTask
import com.thekain.video.domain.*
import com.thekain.video.transfer.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

class TransferCancellationTest {
    private open class Response : HttpsURLConnection(URL("https://owned.invalid/video.mp4")) {
        val disconnects = AtomicInteger()
        override fun getResponseCode() = 200
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf(1))
        override fun getContentLengthLong() = -1L
        override fun disconnect() { disconnects.incrementAndGet() }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "owned-test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private fun task() = DownloadTask("owned", "https://owned.invalid/video.mp4", "video.mp4", "video/mp4")
    private fun stage() = File.createTempFile("cancel-owned", ".part").apply { delete() }

    @Test fun cancellationClosesAStalledResponseBeforeConnectTimeout() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val response = object : Response() {
            override fun getResponseCode(): Int { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return 200 }
            override fun disconnect() { super.disconnect(); release.countDown() }
        }
        val file = stage()
        val work = launch(Dispatchers.Default) { DirectTransfer(Connections { response }).fetch(task(), file, { _, _, _ -> }, { _, _ -> }) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withTimeout(1500) { work.cancelAndJoin() }
            assertTrue(work.isCancelled); assertTrue(response.disconnects.get() > 0); assertFalse(file.exists())
        } finally { release.countDown(); work.cancelAndJoin(); file.delete() }
    }

    @Test fun lateBlockedReadCannotWriteBytesAfterCancellation() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
        val response = object : Response() {
            override fun getInputStream() = object : InputStream() {
                override fun read(): Int = error("Use bounded read")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    buffer[offset] = 99; returned.countDown(); return 1
                }
                override fun close() = Unit // Simulate a platform read which returns late despite close.
            }
        }
        val file = stage()
        val work = launch(Dispatchers.Default) { DirectTransfer(Connections { response }).fetch(task(), file, { _, _, _ -> }, { _, _ -> }) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withTimeout(1500) { work.cancelAndJoin() }
            assertTrue(response.disconnects.get() > 0); assertEquals(0L, file.length())
            release.countDown(); assertTrue(returned.await(5, TimeUnit.SECONDS))
            withTimeout(1500) { while (response.disconnects.get() < 2) delay(5) }
            assertEquals(0L, file.length()) // A late primitive has no staging writer or progress callback.
        } finally { release.countDown(); work.cancelAndJoin(); file.delete() }
    }

    @Test fun lateConnectResultClosesAgainAndCannotStartTheBody() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
        val bodyCalls = AtomicInteger()
        val response = object : Response() {
            override fun getResponseCode(): Int { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); returned.countDown(); return 200 }
            override fun getInputStream(): InputStream { bodyCalls.incrementAndGet(); return super.getInputStream() }
        }
        val file = stage()
        val work = launch(Dispatchers.Default) { DirectTransfer(Connections { response }).fetch(task(), file, { _, _, _ -> }, { _, _ -> }) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); withTimeout(1500) { work.cancelAndJoin() }
            release.countDown(); assertTrue(returned.await(5, TimeUnit.SECONDS))
            withTimeout(1500) { while (response.disconnects.get() < 2) delay(5) }
            assertEquals(0, bodyCalls.get()); assertFalse(file.exists())
        } finally { release.countDown(); work.cancelAndJoin(); file.delete() }
    }

    @Test fun enormousDeclaredSizeCannotOverflowTheFreeSpaceReserve() = runBlocking {
        val response = object : Response() { override fun getContentLengthLong() = Long.MAX_VALUE }
        val file = stage()
        try {
            try { DirectTransfer(Connections { response }, freeBytes = { Long.MAX_VALUE }).fetch(task(), file, { _, _, _ -> }, { _, _ -> }); fail() }
            catch (error: TransferFailure) { assertEquals(TransferError.NO_DISK_SPACE, error.code) }
            assertFalse(file.exists()); assertTrue(response.disconnects.get() > 0)
        } finally { file.delete() }
    }
}
