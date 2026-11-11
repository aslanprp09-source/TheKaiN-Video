package com.thekain.video

import android.content.Context
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thekain.video.domain.*
import com.thekain.video.storage.VerifiedMedia
import com.thekain.video.transfer.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import javax.net.ssl.HttpsURLConnection

/** Host script separates phases with force-stop/relaunch, not a fake repository reset. */
@RunWith(AndroidJUnit4::class)
class ProcessRestartTest {
    @Test fun seedFixture() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("process_phase") == "seed")
        val app = ApplicationProvider.getApplicationContext<TheKaiNApp>()
        val graph = app.graph
        graph.recoveryReady.await()
        val body = InstrumentationRegistry.getInstrumentation().context.assets.open("owned-sample.mp4").use { it.readBytes() }
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        try {
            graph.connections = Connections { url -> (url.openConnection() as HttpsURLConnection).apply { sslSocketFactory = clientTls.sslSocketFactory() } }
            server.enqueue(MockResponse().setBody(Buffer().write(body)))
            val completed = graph.repository.create(DirectLinks.parse(server.url("/process-complete.mp4").toString()))
            assertEquals(EngineResult.COMPLETED, graph.engine.run(completed.id))
            val done = graph.repository.get(completed.id)
            val partial = graph.repository.create(DirectLinks.parse("https://example.invalid/process-partial.mp4"))
            graph.repository.change(partial.id) { it.copy(state = TransferState.DOWNLOADING, bytes = 32) }
            graph.engine.stage(partial).apply { parentFile!!.mkdirs(); writeBytes(body.take(32).toByteArray()) }
            assertTrue(app.getSharedPreferences("m4-process", Context.MODE_PRIVATE).edit()
                .putString("complete_id", done.id).putString("partial_id", partial.id)
                .putString("uri", done.outputUri).putString("digest", done.digest)
                .putLong("size", done.verifiedBytes!!).putInt("seed_pid", Process.myPid()).commit())
        } finally { graph.connections = systemConnections; server.shutdown() }
    }

    @Test fun verifyColdProcess() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("process_phase") == "verify")
        val app = ApplicationProvider.getApplicationContext<TheKaiNApp>()
        val graph = app.graph
        graph.recoveryReady.await()
        val saved = app.getSharedPreferences("m4-process", Context.MODE_PRIVATE)
        assertNotEquals(saved.getInt("seed_pid", -1), Process.myPid())
        val complete = graph.repository.get(saved.getString("complete_id", null)!!)
        val partial = graph.repository.get(saved.getString("partial_id", null)!!)
        assertEquals(TransferState.COMPLETED, complete.state)
        assertEquals(saved.getString("uri", null), complete.outputUri)
        assertEquals(TransferState.FAILED, partial.state)
        assertEquals(TransferError.INTERRUPTED, partial.error)
        graph.verifier.verifyUri(Uri.parse(complete.outputUri), complete.mime,
            VerifiedMedia(saved.getLong("size", 0), saved.getString("digest", null)!!))
        val expected = DirectLinks.publishedName(complete.name, complete.id)
        app.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
            arrayOf(expected, app.packageName), null)!!.use { assertEquals(1, it.count) }
        assertEquals(100, complete.percent)
        assertNotEquals(100, partial.percent)
    }
}
