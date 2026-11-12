package com.thekain.video

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thekain.video.data.*
import com.thekain.video.domain.*
import com.thekain.video.source.*
import com.thekain.video.storage.*
import com.thekain.video.transfer.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class HtmlSourceIntegrationTest {
    @Test fun ownedHttpsHtmlPageOffersActualFilesAndSelectedWebmUsesVerifiedM4Pipeline() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val clips = mapOf("mp4" to "video/mp4", "webm" to "video/webm", "m4a" to "audio/mp4")
            .mapValues { (ext, mime) -> mime to assets.open("owned-sample.$ext").use { it.readBytes() } }
        val poster = assets.open("owned-poster.png").use { it.readBytes() }
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!.substringBefore('?')
                    if (path == "/owned-page") return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("""
                        <title>Наш собственный разрешённый ролик &amp; варианты</title>
                        <video poster="/poster.png"><source src="/owned.mp4?token=owned-fixture"><source src="/owned.webm"><source src="/adaptive.m3u8"></video>
                        <audio src="/owned.m4a"></audio><script src="https://example.invalid/not-executed.js"></script>
                    """.trimIndent())
                    if (path == "/poster.png") return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(poster))
                    val file = clips[path.substringAfterLast('.')] ?: return MockResponse().setResponseCode(404)
                    return MockResponse().setHeader("Content-Type", file.first).apply {
                        if (request.method == "HEAD") setHeader("Content-Length", file.second.size)
                        else setBody(Buffer().write(file.second))
                    }
                }
            }
            start()
        }
        val db = Room.inMemoryDatabaseBuilder(context, DownloadsDb::class.java).build()
        val repo = DownloadRepository(db)
        val root = File(context.cacheDir, "m5-${UUID.randomUUID()}")
        val connections = Connections { url -> (url.openConnection() as HttpsURLConnection).apply { sslSocketFactory = clientTls.sslSocketFactory() } }
        try {
            val source = HttpsSourceAnalyzer(connections).analyze(server.url("/owned-page").toString())
            assertEquals("Наш собственный разрешённый ролик & варианты", source.title)
            assertEquals(listOf("MP4", "WEBM", "M4A"), source.variants.map { it.extension })
            assertArrayEquals(poster, source.poster)
            assertEquals(clips["webm"]!!.second.size.toLong(), source.variants[1].bytes)
            assertTrue(source.variants[2].audioOnly)
            assertFalse(source.toString().contains("token"))
            val chosen = source.variants[1]
            val task = repo.create(chosen.request)
            val verifier = MediaVerifier(context)
            val engine = DownloadEngine(repo, root, DirectTransfer(connections), verifier, MediaPublisher(context, verifier))
            assertEquals(EngineResult.COMPLETED, engine.run(task.id))
            val done = repo.get(task.id)
            assertEquals(100, done.percent)
            assertEquals(chosen.bytes, done.verifiedBytes)
            verifier.verifyUri(Uri.parse(done.outputUri), chosen.request.mime, VerifiedMedia(done.verifiedBytes!!, done.digest!!))
            assertEquals(6, server.requestCount) // Page, three HEADs, owned poster, chosen file. No JS/HLS requests.
        } finally {
            repo.all().mapNotNull { it.outputUri }.forEach { context.contentResolver.delete(Uri.parse(it), null, null) }
            db.close(); root.deleteRecursively(); server.shutdown()
        }
    }
}
