package com.thekain.video

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thekain.video.data.*
import com.thekain.video.domain.*
import com.thekain.video.storage.*
import com.thekain.video.transfer.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Every media byte and TLS certificate is generated/owned by these tests. */
@RunWith(AndroidJUnit4::class)
class M4InstrumentationTest {
    private lateinit var context: Context
    private lateinit var db: DownloadsDb
    private lateinit var repo: DownloadRepository
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var body: ByteArray
    private lateinit var connections: Connections
    private lateinit var verifier: MediaVerifier
    private lateinit var publisher: MediaPublisher
    private val outputs = mutableListOf<Uri>()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, DownloadsDb::class.java).build()
        repo = DownloadRepository(db)
        root = File(context.cacheDir, "m4-" + UUID.randomUUID()).apply { mkdirs() }
        body = InstrumentationRegistry.getInstrumentation().context.assets.open("owned-sample.mp4").use { it.readBytes() }
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        connections = Connections { url -> (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = clientTls.sslSocketFactory() // Only this owned CA; hostname verification stays on.
        } }
        verifier = MediaVerifier(context)
        publisher = MediaPublisher(context, verifier)
    }

    @After fun cleanup() = runBlocking {
        repo.all().mapNotNull { it.outputUri }.forEach { value -> runCatching { context.contentResolver.delete(Uri.parse(value), null, null) } }
        outputs.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        db.close()
        root.deleteRecursively()
        server.shutdown()
    }

    private suspend fun create(path: String = "/owned.mp4") = repo.create(DirectLinks.parse(server.url(path).toString()))
    private fun engine(transfer: TransferAdapter = DirectTransfer(connections), output: OutputPublisher = publisher) =
        DownloadEngine(repo, root, transfer, verifier, output)
    private fun response(bytes: ByteArray = body, chunked: Boolean = false) = MockResponse().apply {
        if (chunked) setChunkedBody(Buffer().write(bytes), 79) else setBody(Buffer().write(bytes))
        setHeader("Content-Type", "video/mp4")
    }

    @Test fun directHttpsMp4IsVerifiedAndMediaStorePendingClearedBeforeCompleted() = runBlocking {
        server.enqueue(response()) // Ignores Range: pause must remain unavailable.
        val task = create()
        val seen = mutableListOf<DownloadTask>()
        assertEquals(EngineResult.COMPLETED, engine().run(task.id) { seen += it })
        val done = repo.get(task.id)
        assertEquals(100, done.percent)
        assertFalse(done.resumable)
        assertEquals(body.size.toLong(), done.verifiedBytes)
        assertEquals(64, done.digest!!.length)
        assertTrue(seen.any { it.state == TransferState.VERIFYING && it.percent == 99 })
        assertTrue(seen.filter { it.state != TransferState.COMPLETED }.all { it.percent != 100 })
        context.contentResolver.query(Uri.parse(done.outputUri), arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        assertEquals(EngineResult.COMPLETED, engine().run(task.id))
        assertEquals(1, server.requestCount) // Completed recovery performs no second download.
        val permissions = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertFalse(permissions.any { it in setOf("android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO") })
    }

    @Test fun unknownSizeStaysUnknownAndOnlyVerifiedOutputCompletes() = runBlocking {
        server.enqueue(response(chunked = true))
        val task = create()
        val seen = mutableListOf<DownloadTask>()
        assertEquals(EngineResult.COMPLETED, engine().run(task.id) { seen += it })
        assertTrue(seen.filter { it.state == TransferState.DOWNLOADING }.all { it.total == null && it.percent == null })
        assertEquals(body.size.toLong(), repo.get(task.id).verifiedBytes)
    }

    @Test fun corruptContainerFailsEvenAfterAllBytesArrive() = runBlocking {
        server.enqueue(response("owned corrupt bytes".toByteArray()))
        val task = create("/corrupt.mp4")
        assertEquals(EngineResult.FAILED, engine().run(task.id))
        val failed = repo.get(task.id)
        assertEquals(TransferError.VERIFY_FAILED, failed.error)
        assertNull(failed.outputUri)
        assertEquals(99, failed.percent)
    }

    @Test fun forbiddenAndMissingAreDistinctAndNeverAutoRetry() = runBlocking {
        for ((code, expected) in listOf(403 to TransferError.REMOTE_FORBIDDEN, 404 to TransferError.REMOTE_MISSING)) {
            server.enqueue(MockResponse().setResponseCode(code))
            val task = create("/$code.mp4")
            assertEquals(EngineResult.FAILED, engine().run(task.id))
            assertEquals(expected, repo.get(task.id).error)
        }
    }

    @Test fun rateLimitAndServerErrorsPersistRetryThenSucceed() = runBlocking {
        for (code in listOf(429, 503)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After", "0"))
            server.enqueue(response())
            val task = create("/$code.mp4")
            val pipeline = engine()
            assertEquals(EngineResult.RETRY, pipeline.run(task.id))
            assertEquals(TransferState.RETRY_WAIT, repo.get(task.id).state)
            assertEquals(EngineResult.COMPLETED, pipeline.run(task.id))
        }
    }

    @Test fun noSpaceNeverPublishesAndCanBeRetriedExplicitly() = runBlocking {
        server.enqueue(response())
        val task = create()
        assertEquals(EngineResult.FAILED, engine(DirectTransfer(connections, freeBytes = { 0 })).run(task.id))
        assertEquals(TransferError.NO_DISK_SPACE, repo.get(task.id).error)
        assertNull(repo.get(task.id).outputUri)
        repo.retry(task.id)
        server.enqueue(response())
        assertEquals(EngineResult.COMPLETED, engine().run(task.id))
        assertEquals(2, repo.get(task.id).attempt)
    }

    @Test fun duplicateNamesProduceDistinctRowsWithoutOverwriting() = runBlocking {
        server.enqueue(response()); server.enqueue(response())
        val first = create(); val second = create()
        assertEquals(EngineResult.COMPLETED, engine().run(first.id))
        assertEquals(EngineResult.COMPLETED, engine().run(second.id))
        val a = repo.get(first.id); val b = repo.get(second.id)
        assertNotEquals(a.outputUri, b.outputUri)
        val names = listOf(a, b).map { task -> context.contentResolver.query(Uri.parse(task.outputUri),
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use { it.moveToFirst(); it.getString(0) } }
        assertNotEquals(names[0], names[1])
        assertEquals(a.digest, b.digest)
    }

    @Test fun queuedCancelNeverStartsNetworkOrPublishes() = runBlocking {
        val task = create()
        assertTrue(repo.requestCancel(task.id))
        assertEquals(EngineResult.STOPPED, engine().run(task.id))
        assertEquals(TransferState.CANCELED, repo.get(task.id).state)
        assertEquals(0, server.requestCount)
        assertNull(repo.get(task.id).outputUri)
    }

    @Test fun activeCancelAcknowledgesWithoutPublishingEvenAtFullTransfer() = runBlocking {
        server.enqueue(response())
        val task = create()
        var requested = false
        assertEquals(EngineResult.STOPPED, engine().run(task.id) {
            if (!requested && it.state == TransferState.DOWNLOADING && it.bytes > 0) {
                requested = repo.requestCancel(task.id)
            }
        })
        assertTrue(requested)
        assertEquals(TransferState.CANCELED, repo.get(task.id).state)
        assertNull(repo.get(task.id).outputUri)
    }

    @Test fun realPauseAcknowledgesAndResumeRevalidatesRange() = runBlocking {
        // A valid ISO BMFF free box pads our owned clip so the transfer can pause.
        val padding = java.nio.ByteBuffer.allocate(512 * 1024).apply {
            putInt(capacity()); put("free".toByteArray(Charsets.US_ASCII))
        }.array()
        val clip = body + padding
        server.enqueue(response(clip.take(1).toByteArray()).setResponseCode(206)
            .setHeader("Content-Range", "bytes 0-0/${clip.size}").setHeader("ETag", "\"owned\""))
        server.enqueue(response(clip).setHeader("ETag", "\"owned\"")
            .throttleBody(4096, 10, java.util.concurrent.TimeUnit.MILLISECONDS))
        val task = create("/pause.mp4")
        val pipeline = engine()
        var requested = false
        assertEquals(EngineResult.STOPPED, pipeline.run(task.id) {
            if (!requested && it.state == TransferState.DOWNLOADING && it.resumable && it.bytes > 0)
                requested = repo.requestPause(task.id)
        })
        val paused = repo.get(task.id)
        assertEquals(TransferState.PAUSED, paused.state)
        val offset = pipeline.stage(paused).length()
        assertTrue(offset > 0 && offset < clip.size)
        repo.resume(task.id)
        server.enqueue(response(clip.drop(offset.toInt()).toByteArray()).setResponseCode(206)
            .setHeader("ETag", "\"owned\"").setHeader("Content-Range", "bytes $offset-${clip.size - 1}/${clip.size}"))
        assertEquals(EngineResult.COMPLETED, pipeline.run(task.id))
        server.takeRequest(); server.takeRequest()
        assertEquals("bytes=$offset-", server.takeRequest().getHeader("Range"))
    }

    @Test fun publicationNoSpaceRetainsPendingIntentAndRetryReusesUri() = runBlocking {
        server.enqueue(response())
        val task = create()
        val full = object : OutputPublisher by publisher {
            override suspend fun publish(task: DownloadTask, file: File, uri: String, media: VerifiedMedia) {
                throw TransferFailure(TransferError.NO_DISK_SPACE)
            }
        }
        assertEquals(EngineResult.FAILED, engine(output = full).run(task.id))
        val failed = repo.get(task.id)
        assertEquals(TransferError.NO_DISK_SPACE, failed.error)
        assertNotNull(failed.outputUri)
        assertNotEquals(100, failed.percent)
        repo.retry(task.id)
        assertEquals(EngineResult.COMPLETED, engine().run(task.id))
        assertEquals(failed.outputUri, repo.get(task.id).outputUri)
        assertEquals(1, server.requestCount)
    }

    @Test fun interruptedUnprovenPartialDoesNotPretendToResume() = runBlocking {
        val task = create()
        repo.change(task.id) { it.copy(state = TransferState.DOWNLOADING) }
        val pipeline = engine()
        pipeline.stage(task).apply { parentFile!!.mkdirs(); writeBytes(body.take(100).toByteArray()) }
        assertEquals(EngineResult.FAILED, pipeline.run(task.id))
        assertEquals(TransferError.INTERRUPTED, repo.get(task.id).error)
        assertEquals(0, server.requestCount)
    }

    @Test fun persistedPartialReopensRoomAndResumesOnlyMatching206() = runBlocking {
        val dbName = "m4-reopen-${UUID.randomUUID()}.db"
        var disk = Room.databaseBuilder(context, DownloadsDb::class.java, dbName).build()
        try {
            val before = DownloadRepository(disk)
            val task = before.create(DirectLinks.parse(server.url("/resume.mp4").toString()))
            before.change(task.id) { it.copy(state = TransferState.DOWNLOADING, bytes = 100, total = body.size.toLong(), etag = "\"owned\"", resumable = true) }
            val partial = File(root, "${task.id}/1/payload.part").apply { parentFile!!.mkdirs(); writeBytes(body.take(100).toByteArray()) }
            disk.close()
            disk = Room.databaseBuilder(context, DownloadsDb::class.java, dbName).build()
            val after = DownloadRepository(disk)
            server.enqueue(response(body.drop(100).toByteArray()).setResponseCode(206)
                .setHeader("ETag", "\"owned\"").setHeader("Content-Range", "bytes 100-${body.size - 1}/${body.size}"))
            val pipeline = DownloadEngine(after, root, DirectTransfer(connections), verifier, publisher)
            assertEquals(EngineResult.COMPLETED, pipeline.run(task.id))
            assertArrayEquals(body, partial.readBytes())
            val uri = Uri.parse(after.get(task.id).outputUri)
            outputs += uri
            assertEquals("bytes=100-", server.takeRequest().getHeader("Range"))
        } finally { disk.close(); context.deleteDatabase(dbName) }
    }

    @Test fun publicationRecoveryReusesExistingUriWithoutNetwork() = runBlocking {
        server.enqueue(response())
        val task = create()
        var saved = false
        val interrupted = object : OutputPublisher by publisher {
            override suspend fun publish(task: DownloadTask, file: File, uri: String, media: VerifiedMedia) {
                publisher.publish(task, file, uri, media)
                saved = true
                throw CancellationException("owned process-stop window")
            }
        }
        try { engine(output = interrupted).run(task.id); fail() } catch (_: CancellationException) { }
        assertTrue(saved)
        val pending = repo.get(task.id)
        assertEquals(TransferState.PUBLISHING, pending.state)
        val uri = pending.outputUri
        assertEquals(EngineResult.COMPLETED, engine().run(task.id))
        assertEquals(uri, repo.get(task.id).outputUri)
        assertEquals(1, server.requestCount)
    }

    @Test fun legacyAppSpecificPublicationUsesFileProviderWithoutStoragePermission() = runBlocking {
        server.enqueue(response())
        val task = create()
        val legacy = MediaPublisher(context, verifier, api = 28)
        assertEquals(EngineResult.COMPLETED, engine(output = legacy).run(task.id))
        val uri = Uri.parse(repo.get(task.id).outputUri)
        assertEquals(context.packageName + ".files", uri.authority)
        assertEquals(body.size.toLong(), repo.get(task.id).verifiedBytes)
        // Only the owned test file, never a public Downloads path.
        File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), DirectLinks.publishedName(task.name, task.id)).delete()
    }

    @Test fun activityRecreationUsesSameWorkAndCompletedRecord() = runBlocking { activityTransfer(true) }

    internal suspend fun activityTransfer(grant: Boolean) {
        val app = context.applicationContext as TheKaiNApp
        val graph = app.graph
        graph.recoveryReady.await()
        graph.connections = connections
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            assertEquals(if (grant) android.content.pm.PackageManager.PERMISSION_GRANTED else android.content.pm.PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
        }
        server.enqueue(response())
        val source = server.url("/activity.mp4").toString()
        var id: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<android.widget.EditText>(R.id.source_input).setText(source)
                    activity.findViewById<android.widget.Button>(R.id.download_button).performClick()
                }
                withTimeout(90_000) {
                    while (true) {
                        val own = graph.repository.all().filter { it.url == source }
                        if (own.size == 1 && own[0].state == TransferState.COMPLETED) { id = own[0].id; break }
                        delay(100)
                    }
                }
                val output = graph.repository.get(id!!).outputUri
                scenario.recreate()
                delay(300)
                assertEquals(1, graph.repository.all().count { it.id == id })
                assertEquals(output, graph.repository.get(id!!).outputUri)
                scenario.onActivity { activity ->
                    val list = activity.findViewById<android.widget.LinearLayout>(R.id.task_list)
                    assertEquals(1, (0 until list.childCount).count { list.getChildAt(it).tag == id })
                }
                assertEquals(1, server.requestCount)
                withTimeout(30_000) {
                    while (androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("download-$id").get().any { !it.state.isFinished }) delay(50)
                }
            }
        } finally {
            id?.let { value ->
                val job = graph.repository.get(value)
                job.outputUri?.let { outputs += Uri.parse(it) }
                graph.db.openHelper.writableDatabase.execSQL("DELETE FROM downloads WHERE id=?", arrayOf(value))
            }
            graph.connections = systemConnections

        }
    }
}
