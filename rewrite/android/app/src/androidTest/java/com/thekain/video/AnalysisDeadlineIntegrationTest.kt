package com.thekain.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thekain.video.source.*
import com.thekain.video.transfer.Connections
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Real Android URLConnection cancellation against an owned TLS server; no external site. */
@RunWith(AndroidJUnit4::class)
class AnalysisDeadlineIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var connections: Connections
    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); start() }
        connections = Connections { url -> (url.openConnection() as HttpsURLConnection).apply { sslSocketFactory = trusted.sslSocketFactory() } }
    }
    @After fun cleanup() { server.shutdown() }

    @Test fun trickleResponseTimesOutEvenWhileBytesKeepArriving() = runBlocking<Unit> {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html")
            .setBody("<title>Owned slow page</title>" + " ".repeat(4096))
            .throttleBody(1, 100, TimeUnit.MILLISECONDS))
        val started = System.nanoTime()
        try { HttpsSourceAnalyzer(connections, timeoutMillis = 2500).analyze(server.url("/slow").toString()); fail("Slow page became ready") }
        catch (failure: AnalysisFailure) { assertEquals(AnalysisError.TIMEOUT, failure.code) }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("Deadline did not release the caller: $elapsed ms", elapsed < 8000)
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
    }

    @Test fun cancelDuringBlockedHeadersDoesNotBlockMainThreadOrReturnReady() = runBlocking<Unit> {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html")
            .setHeadersDelay(4, TimeUnit.SECONDS).setBody("<video src='owned.mp4'></video>"))
        val pending = async(Dispatchers.Default) { HttpsSourceAnalyzer(connections, timeoutMillis = 15_000).analyze(server.url("/blocked").toString()) }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { pending.cancel() }
        pending.join()
        assertTrue(pending.isCancelled)
        assertTrue("Cancellation blocked the main thread", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000)
    }
}
