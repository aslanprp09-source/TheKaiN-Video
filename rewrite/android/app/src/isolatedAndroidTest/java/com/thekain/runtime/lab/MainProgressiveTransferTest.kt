package com.thekain.runtime.lab

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thekain.video.BuildConfig
import com.thekain.video.data.*
import com.thekain.video.domain.*
import com.thekain.video.storage.*
import com.thekain.video.transfer.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import okio.Buffer
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainProgressiveTransferTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: DownloadsDb
    private lateinit var repo: DownloadRepository
    private lateinit var server: MockWebServer
    private lateinit var policy: RemoteHttpsTransport.Policy
    private lateinit var root: File
    private lateinit var body: ByteArray
    private lateinit var publisher: MediaPublisher
    private val proofs = JSONArray()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, DownloadsDb::class.java).build(); repo = DownloadRepository(db)
        root = File(context.cacheDir, "progressive-${UUID.randomUUID()}").apply { mkdirs() }
        body = InstrumentationRegistry.getInstrumentation().context.assets.open("owned-sample.mp4").use { it.readBytes() }
        val cert = HeldCertificate.Builder().commonName("owned").addSubjectAlternativeName("127.0.0.1").build()
        server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        policy = RemoteHttpsTransport.Policy.fixture("https://127.0.0.1:${server.port}", cert.certificate.encoded)
        publisher = MediaPublisher(context, MediaVerifier(context))
    }
    @After fun cleanup() = runBlocking<Unit> {
        try { repo.all().filter { it.state == TransferState.COMPLETED }.forEach { publisher.deleteCompletedOutput(it) } }
        finally { db.close(); root.deleteRecursively(); server.shutdown() }
    }
    private suspend fun task() = repo.create(DirectLinks.parse(server.url("/${UUID.randomUUID()}.mp4").toString())
        .copy(backend = TransferBackend.ISOLATED_OWNED))
    private fun response(bytes: ByteArray = body) = MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes))
    private fun engine(adapter: TransferAdapter) = DownloadEngine(repo, root, adapter, MediaVerifier(context), publisher)
    private fun proof(adapter: IsolatedProgressiveTransfer) {
        val proof = requireNotNull(adapter.lastEvidence)
        assertTrue(proof.getBoolean("terminated")); assertTrue(proof.getBoolean("lease_clean"))
        assertTrue(proof.getBoolean("kernel_process_gone")); assertTrue(proof.getBoolean("service_absent")); assertTrue(proof.getBoolean("binder_dead"))
        assertTrue(proof.getBoolean("transfer_only")); assertFalse(proof.getBoolean("owner_python_loaded"))
        assertFalse(proof.getBoolean("verified_media")); proofs.put(proof)
    }
    private fun record(name: String, details: JSONObject) {
        val folder = File(context.filesDir, "main-progressive-evidence").apply { mkdirs() }
        folder.resolve("$name.json").writeText(details.put("case", name).put("source_commit", BuildConfig.INTERNAL_RUNTIME_SOURCE)
            .put("api", android.os.Build.VERSION.SDK_INT).put("pages", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
            .put("proofs", proofs).put("production_ready", false).toString())
    }
    @Test fun corruptMediaNeverCompletesAndExplicitRetryVerifiesFreshBytes() = runBlocking<Unit> {
        server.enqueue(response(byteArrayOf(0, 0, 0, 12) + "ftypbroken".toByteArray()))
        val task = task(); val adapter = IsolatedProgressiveTransfer(context, policy); val engine = engine(adapter)
        assertEquals(EngineResult.FAILED, engine.run(task.id)); proof(adapter)
        assertEquals(TransferError.VERIFY_FAILED, repo.get(task.id).error); assertNull(repo.get(task.id).outputUri)
        assertTrue(requireNotNull(adapter.lastEvidence).getBoolean("transfer_receipt")) // Receipt alone is never completion.
        repo.retry(task.id); server.enqueue(response())
        assertEquals(EngineResult.COMPLETED, engine.run(task.id)); proof(adapter)
        val completed = repo.get(task.id)
        assertEquals(2, completed.attempt); assertEquals(TransferBackend.ISOLATED_OWNED, completed.backend)
        assertEquals(body.size.toLong(), completed.verifiedBytes); assertFalse(completed.resumable)
        assertArrayEquals(body, context.contentResolver.openInputStream(android.net.Uri.parse(completed.outputUri))!!.use { it.readBytes() })
        assertEquals(EngineResult.COMPLETED, engine.run(task.id)); assertEquals(2, server.requestCount)
        record("verification", JSONObject().put("corrupt_refused", true).put("receipt_not_completion", true).put("retry_fresh", true).put("verified_bytes", body.size))
    }
    @Test fun cancelAndActualDeadlineLeaveNoOutputOrLateWritesThenRecover() = runBlocking<Unit> {
        for (cancel in listOf(true, false)) {
            server.enqueue(response().throttleBody(1, 250, TimeUnit.MILLISECONDS))
            val task = task(); val adapter = IsolatedProgressiveTransfer(context, policy, if (cancel) 10_000 else 5000)
            val engine = engine(adapter)
            val work = async(Dispatchers.IO) { engine.run(task.id) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            if (cancel) { assertTrue(repo.requestCancel(task.id)); withTimeout(3500) { work.cancelAndJoin() } }
            else { assertEquals(EngineResult.FAILED, withTimeout(8500) { work.await() }); assertEquals(TransferError.TIMEOUT, repo.get(task.id).error) }
            val row = repo.get(task.id)
            assertEquals(if (cancel) TransferState.CANCELED else TransferState.FAILED, row.state)
            assertNull(row.outputUri); assertNull(row.digest); assertFalse(row.resumable); proof(adapter)
            if (!cancel) assertEquals(RuntimeResult.TIMEOUT, requireNotNull(adapter.lastEvidence).getInt("failure"))
            val bytes = engine.stage(task).length(); delay(200); assertEquals(bytes, engine.stage(task).length())
            server.enqueue(response()); val fresh = task(); val freshAdapter = IsolatedProgressiveTransfer(context, policy)
            assertEquals(EngineResult.COMPLETED, engine(freshAdapter).run(fresh.id)); proof(freshAdapter)
            // Consume the successful request before observing the next deadline's actual request.
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        }
        record("lifecycle", JSONObject().put("cancel", true).put("deadline", true).put("actual_http", true).put("no_late_writes", true).put("fresh_after_each", true))
    }
    @Test fun noSpaceAndPersistedOutOfScopeBackendCannotUseDirectTransfer() = runBlocking<Unit> {
        server.enqueue(response()); val task = task()
        val adapter = IsolatedProgressiveTransfer(context, policy, freeBytes = { 0 })
        assertEquals(EngineResult.FAILED, engine(adapter).run(task.id)); proof(adapter)
        assertEquals(TransferError.NO_DISK_SPACE, repo.get(task.id).error); assertNull(repo.get(task.id).outputUri)
        val unexpected = task()
        val factory = TransferAdapterFactory.create(context) { throw AssertionError("Isolated job was downgraded to direct") }
        assertEquals(EngineResult.FAILED, engine(factory).run(unexpected.id))
        assertEquals(TransferError.SOURCE_POLICY, repo.get(unexpected.id).error)
        assertEquals(1, server.requestCount)
        record("storage", JSONObject().put("no_space", true).put("scope_refused", true).put("direct_downgrade_refused", true))
    }
    @Test fun migrationPreservesVersionOneHistoryAndAssignsOnlyDirectBackend() = runBlocking<Unit> {
        val name = "migration-${UUID.randomUUID()}.db"
        val old = context.openOrCreateDatabase(name, android.content.Context.MODE_PRIVATE, null)
        try {
            old.execSQL("CREATE TABLE downloads (id TEXT NOT NULL PRIMARY KEY, url TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, bytes INTEGER NOT NULL, total INTEGER, etag TEXT, resumable INTEGER NOT NULL, attempt INTEGER NOT NULL, failures INTEGER NOT NULL, eligibleAt INTEGER NOT NULL, error TEXT, outputUri TEXT, verifiedBytes INTEGER, digest TEXT)")
            old.execSQL("INSERT INTO downloads VALUES ('old-complete','https://owned.example.org/old.mp4','old.mp4','video/mp4','COMPLETED',1,2,2428,2428,NULL,0,1,0,0,NULL,'content://existing/42',2428,'old-digest')")
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES (42,'8679d24229a1e96afed64d907b14cce8')")
            old.version = 1
        } finally { old.close() }
        val migrated = Room.databaseBuilder(context, DownloadsDb::class.java, name).addMigrations(DownloadsDb.MIGRATION_1_2).build()
        try {
            val preserved = DownloadRepository(migrated).get("old-complete")
            assertEquals(TransferState.COMPLETED, preserved.state); assertEquals("content://existing/42", preserved.outputUri)
            assertEquals("old-digest", preserved.digest); assertEquals(2428L, preserved.verifiedBytes)
            assertEquals(TransferBackend.DIRECT, preserved.backend)
            val row = DownloadRepository(migrated).create(DirectLinks.parse(server.url("/persist.mp4").toString()).copy(backend = TransferBackend.ISOLATED_OWNED))
            assertEquals(TransferBackend.ISOLATED_OWNED, row.backend)
        } finally { migrated.close() }
        val reopened = Room.databaseBuilder(context, DownloadsDb::class.java, name).build()
        try { assertTrue(DownloadRepository(reopened).all().any { it.backend == TransferBackend.ISOLATED_OWNED }); assertEquals(2, reopened.openHelper.readableDatabase.version) }
        finally { reopened.close(); context.deleteDatabase(name) }
        record("migration", JSONObject().put("v1_history_preserved", true).put("old_backend_direct", true).put("new_backend_durable", true))
    }
}
