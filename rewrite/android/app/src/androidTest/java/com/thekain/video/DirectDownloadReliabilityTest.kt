package com.thekain.video

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.thekain.video.data.*
import com.thekain.video.domain.*
import com.thekain.video.storage.*
import com.thekain.video.transfer.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Separate additional gate: the original API35 inventory must still execute all 41 tests. */
@RunWith(AndroidJUnit4::class)
class DirectDownloadReliabilityTest {
    private lateinit var context: Context
    private lateinit var db: DownloadsDb
    private lateinit var repo: DownloadRepository
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var body: ByteArray
    private lateinit var connections: Connections
    private lateinit var verifier: MediaVerifier
    private lateinit var publisher: MediaPublisher
    private val databaseName = "direct-reliability-${UUID.randomUUID()}.db"

    private fun openDatabase() {
        db = Room.databaseBuilder(context, DownloadsDb::class.java, databaseName).build()
        repo = DownloadRepository(db)
    }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        openDatabase()
        root = File(context.cacheDir, databaseName).apply { mkdirs() }
        body = InstrumentationRegistry.getInstrumentation().context.assets.open("owned-sample.mp4").use { it.readBytes() }
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); start() }
        connections = Connections { (it.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = trust.sslSocketFactory() // Only our fixture CA; hostname checks remain enabled.
        } }
        verifier = MediaVerifier(context)
        publisher = MediaPublisher(context, verifier)
    }

    @After fun cleanup() = runBlocking<Unit> {
        repo.all().filter { it.state == TransferState.COMPLETED }.forEach { publisher.deleteCompletedOutput(it) }
        db.close()
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
        server.shutdown()
    }

    private fun engine() = DownloadEngine(repo, root, DirectTransfer(connections), verifier, publisher)
    private fun response() = MockResponse().setBody(Buffer().write(body)).setHeader("Content-Type", "video/mp4")
    private suspend fun task(path: String) = repo.create(DirectLinks.parse(server.url(path).toString()))
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun stalledHttpsBodyCanBeCanceledWithoutPublicationOrLateWrites() = runBlocking<Unit> {
        server.enqueue(response().setBodyDelay(3, TimeUnit.SECONDS))
        val task = task("/stalled.mp4")
        val engine = engine()
        val work = launch(Dispatchers.IO) { engine.run(task.id) }
        try {
            withTimeout(10_000) { while (!engine.stage(task).isFile) delay(10) }
            delay(100) // The real HTTPS stream is now awaiting the delayed body.
            assertTrue(repo.requestCancel(task.id))
            val started = SystemClock.elapsedRealtime()
            withTimeout(1500) { work.cancelAndJoin() }
            assertTrue("Cancellation waited for the five-second socket timeout", SystemClock.elapsedRealtime() - started < 1500)
            val canceled = repo.get(task.id)
            assertEquals(TransferState.CANCELED, canceled.state)
            assertNull(canceled.outputUri)
            assertNull(canceled.digest)
            val bytes = engine.stage(task).length()
            delay(250)
            assertEquals(bytes, engine.stage(task).length())
            assertEquals(TransferState.CANCELED, repo.get(task.id).state)
        } finally { work.cancelAndJoin() }
    }

    @Test fun truncatedNonRangeResponseRetriesFromZeroAcrossDatabaseReopen() = runBlocking<Unit> {
        server.enqueue(response().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val task = task("/retry-owned.mp4")
        val first = engine()
        assertEquals(EngineResult.RETRY, first.run(task.id))
        val partial = first.stage(task).length()
        assertTrue("The fixture must actually leave a partial response", partial in 1 until body.size.toLong())
        val waiting = repo.get(task.id)
        assertEquals(TransferState.RETRY_WAIT, waiting.state)
        assertFalse(waiting.resumable)
        assertNull(waiting.outputUri)
        db.close()
        openDatabase()
        assertEquals(waiting, repo.get(task.id))
        server.enqueue(response()) // Ignores Range and has no strong validator: a complete fresh response.
        val restarted = engine()
        assertEquals(EngineResult.COMPLETED, restarted.run(task.id))
        val completed = repo.get(task.id)
        assertEquals(1, completed.attempt)
        assertEquals(1, completed.failures)
        assertEquals(body.size.toLong(), completed.verifiedBytes)
        assertEquals(digest(body), completed.digest)
        assertArrayEquals(body, context.contentResolver.openInputStream(Uri.parse(completed.outputUri))!!.use { it.readBytes() })
        assertEquals(EngineResult.COMPLETED, restarted.run(task.id))
        assertEquals(2, server.requestCount)
        assertEquals(1, repo.all().size)
        assertEquals(completed.outputUri, repo.get(task.id).outputUri)
    }

    @Test fun mainUiAnalyzesDownloadsPlaysAndRetainsVerifiedHistory() = runBlocking<Unit> {
        val graph = (context.applicationContext as TheKaiNApp).graph
        graph.recoveryReady.await()
        graph.connections = connections
        val source = server.url("/ui-${UUID.randomUUID()}.mp4").toString()
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setHeader("Content-Length", body.size))
        server.enqueue(response())
        var ownId: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var activity: MainActivity? = null
                scenario.onActivity { activity = it }
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                try {
                    UiWindowEvidence.await(requireNotNull(activity), "direct-source")
                    requireNotNull(device.wait(Until.findObject(By.res("source_input")), 10_000)).text = source
                    UiWindowEvidence.await(requireNotNull(activity), "direct-analyze")
                    requireNotNull(device.wait(Until.findObject(By.res("analyze_button")), 10_000)).click()
                    val download = requireNotNull(device.wait(Until.findObject(By.res("download_button")), 15_000))
                    UiWindowEvidence.await(requireNotNull(activity), "direct-download")
                    download.click()
                    val completed = withTimeout(90_000) {
                        while (true) {
                            val own = graph.repository.all().filter { it.url == source }
                            assertTrue("One click created duplicate tasks", own.size <= 1)
                            own.singleOrNull()?.let { row ->
                                ownId = row.id
                                assertNotEquals("Owned HTTPS download failed: ${row.error}", TransferState.FAILED, row.state)
                                if (row.state == TransferState.COMPLETED) return@withTimeout row
                            }
                            delay(50)
                        }
                        @Suppress("UNREACHABLE_CODE") error("Unreachable")
                    }
                    assertEquals(body.size.toLong(), completed.verifiedBytes)
                    assertEquals(digest(body), completed.digest)
                    // Open the actual published URI with Android's real media player, not a fake verifier.
                    withContext(Dispatchers.IO) {
                        val player = MediaPlayer()
                        try {
                            player.setDataSource(context, Uri.parse(completed.outputUri))
                            player.prepare()
                            assertTrue(player.duration > 0)
                            player.start()
                            withTimeout(10_000) { while (player.currentPosition == 0) delay(20) }
                        } finally { player.release() }
                    }
                    UiWindowEvidence.await(requireNotNull(activity), "direct-before-recreate")
                    scenario.recreate()
                    scenario.onActivity { activity = it }
                    UiWindowEvidence.await(requireNotNull(activity), "direct-after-recreate")
                    // Reopen a separate Room connection to verify durable history, not only an in-memory flow.
                    val reopened = Room.databaseBuilder(context, DownloadsDb::class.java, "downloads.db").build()
                    try { assertEquals(completed, DownloadRepository(reopened).get(completed.id)) }
                    finally { reopened.close() }
                    assertEquals(1, graph.repository.all().count { it.url == source })
                    assertEquals(2, server.requestCount) // One real analysis HEAD plus one real transfer.
                    scenario.onActivity {
                        it.currentFocus?.clearFocus()
                        androidx.core.view.WindowInsetsControllerCompat(it.window, it.window.decorView)
                            .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                    }
                    withTimeout(10_000) {
                        while (true) {
                            var visible = false
                            scenario.onActivity {
                                visible = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                            }
                            if (!visible) break
                            delay(50)
                        }
                    }
                    UiWindowEvidence.await(requireNotNull(activity), "direct-history-navigation")
                    requireNotNull(device.wait(Until.findObject(By.res("nav_downloads")), 10_000)).click()
                    assertTrue("Verified history row absent", device.wait(Until.hasObject(By.res("task_${completed.id}")), 10_000))
                    assertTrue("Completed file has no open action", device.wait(Until.hasObject(By.res("open_${completed.id}")), 10_000))
                    UiWindowEvidence.capture("direct-verified-history", activity)
                } catch (failure: Throwable) {
                    UiWindowEvidence.capture("direct-ui-failure", activity)
                    throw failure
                }
            }
        } finally {
            try {
                ownId?.let { id ->
                    withTimeout(30_000) {
                        while (androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("download-$id").get().any { !it.state.isFinished }) delay(50)
                    }
                    val own = graph.repository.get(id)
                    if (own.state == TransferState.COMPLETED) graph.removeCompleted(id, removeFile = true)
                }
            } finally { graph.connections = systemConnections }
        }
    }
}
