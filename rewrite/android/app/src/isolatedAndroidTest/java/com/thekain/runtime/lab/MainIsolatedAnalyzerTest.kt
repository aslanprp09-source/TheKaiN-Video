package com.thekain.runtime.lab

import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thekain.video.*
import com.thekain.video.source.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Actual existing application ID + SourceAnalyzer + verified resources + isolated native CPython + remote TLS. */
@RunWith(AndroidJUnit4::class)
class MainIsolatedAnalyzerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var origin: String
    private lateinit var policy: RemoteHttpsTransport.Policy
    @Before fun setup() {
        assertEquals("com.thekain.video.rebuild", context.packageName)
        val certificate = HeldCertificate.Builder().commonName("owned").addSubjectAlternativeName("127.0.0.1").build()
        server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val html = """<html><title>Owned main APK</title><video><source src='/owned.mp4' type='video/mp4; codecs="avc1.42E01E, mp4a.40.2"'></video></html>"""
                    return when {
                        request.path!!.startsWith("/stall-deadline") -> MockResponse().setHeader("Content-Type", "text/html").setBody(html).throttleBody(1, 250, TimeUnit.MILLISECONDS)
                        request.path!!.startsWith("/stall") -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        else -> MockResponse().setHeader("Content-Type", "text/html").setBody(html)
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        origin = "https://127.0.0.1:${server.port}"
        policy = RemoteHttpsTransport.Policy.fixture(origin, certificate.certificate.encoded)
    }
    @After fun cleanup() { server.shutdown() }
    private fun rows(): List<JSONObject> = File(context.filesDir, "isolated-source-evidence.jsonl").takeIf { it.exists() }
        ?.readLines()?.filter { it.isNotBlank() }?.map(::JSONObject) ?: emptyList()
    private fun proof(row: JSONObject) {
        assertEquals(BuildConfig.INTERNAL_RUNTIME_SOURCE, row.getString("source_commit"))
        assertTrue(row.getBoolean("death_confirmed")); assertTrue(row.getBoolean("canary_intact"))
        assertNotEquals(row.getInt("owner_uid"), row.getInt("worker_uid"))
        assertEquals(255, row.getInt("child_protection")); assertFalse(row.getBoolean("observer_python_loaded"))
        val network = row.getJSONObject("network")
        assertTrue(network.getBoolean("kernel_process_gone")); assertTrue(network.getBoolean("lease_clean")); assertTrue(network.getBoolean("terminated"))
        assertFalse(network.getBoolean("owner_python_loaded")); assertNotEquals(android.os.Process.myPid(), network.getInt("pid"))
        assertEquals(android.os.Process.myUid(), network.getInt("ordinary_uid"))
        assertTrue(network.getLong("peak_rss_bytes") > 0); assertTrue(row.getLong("peak_rss_bytes") > 0)
    }
    private fun save(name: String, facts: JSONObject) {
        val folder = File(context.filesDir, "main-isolated-evidence").apply { mkdirs() }
        facts.put("case", name).put("source_commit", BuildConfig.INTERNAL_RUNTIME_SOURCE).put("api", Build.VERSION.SDK_INT)
            .put("pages", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)).put("production_ready", false)
        File(folder, "$name.json").writeText(facts.toString())
    }
    @Test fun actualAppGraphRoutesOnlyOwnedPageAndPreservesDirectHttps() = runBlocking {
        val graph = (context.applicationContext as TheKaiNApp).graph
        val before = rows().size
        val source = graph.sourceAnalyzer.analyze(SourceAnalyzerFactory.ownedPage)
        assertTrue(source.title.startsWith("Owned isolated source")); assertEquals("raw.githubusercontent.com", source.host)
        assertEquals(1, source.variants.size)
        val format = source.variants.single()
        assertFalse(format.downloadAllowed); assertNull(format.bytes); assertNull(format.width); assertNull(format.height); assertNull(format.fps)
        assertNull(format.videoCodec); assertEquals("mp4a.40.2", format.audioCodec)
        val completed = rows(); assertEquals(before + 1, completed.size); val row = completed.last(); proof(row)
        assertEquals(0, row.getInt("error")); assertEquals(1, row.getLong("python_calls")); assertEquals("x86_64", row.getString("machine"))
        val direct = graph.sourceAnalyzer.analyze("https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/11fa103624275958f089d9566c7722fc64133094/rewrite/android/app/src/androidTest/assets/owned-sample.mp4")
        assertEquals(1, direct.variants.size); assertTrue(direct.variants.single().downloadAllowed); assertEquals(2428L, direct.variants.single().bytes)
        assertEquals(completed.size, rows().size) // Direct path never silently invokes Python.
        save("public", JSONObject().put("actual_graph", true).put("typed_formats", true).put("unknowns_preserved", true)
            .put("download_blocked", true).put("direct_preserved", true).put("runtime", row))
    }
    private suspend fun awaitResult(analyzer: IsolatedSourceAnalyzer, previous: String? = null) {
        withTimeout(5000) { while (analyzer.lastOutcome == null || analyzer.lastOutcome!!.result.id == previous || analyzer.lastTransport == null) delay(10) }
        proof(rows().last())
    }
    @Test fun earlyCancellationStopsAssetVerificationAndNeverAuthorizesPythonOrLeavesTemporaryFiles() = runBlocking {
        val id = java.util.UUID.randomUUID().toString()
        val request = RuntimeRequest(id, RuntimeRequest.SOURCE_ANALYZE, "$origin/page", 1, SystemClock.elapsedRealtime() + 10000, RuntimeRequest.MAX_RSS, RuntimeRequest.MAX_HEADROOM)
        val lease = IsolatedLease(context); val guard = lease.createGuard(id)
        val checks = java.util.concurrent.atomic.AtomicInteger()
        try {
            try { IsolatedResources.prepare(context, request, 0, null, guard) { checks.incrementAndGet() > 6 }; fail("Canceled resource preparation completed") }
            catch (expected: java.io.IOException) { assertEquals("RESOURCE_RETIRED", expected.message) }
        } finally { assertTrue(lease.finishKnownDead(id, guard)) }
        assertTrue(checks.get() > 6)
        val temporary = context.cacheDir.listFiles { file -> file.name.startsWith("isolated-python-") || file.name.startsWith("isolated-fixture-") }!!
        assertEquals(0, temporary.size)
        val runtime = IsolatedRuntime.forSourceAnalysis(context, policy)
        val outcome = try {
            val op = runtime.analyzeSource("$origin/page", 10000); assertTrue(op.cancel())
            op.outcomeSignal().get(5, TimeUnit.SECONDS)
        } finally { runtime.close() }
        assertEquals(RuntimeResult.CANCELED, outcome.result.error); assertTrue(outcome.deathConfirmed); assertFalse(outcome.pythonEntered)
        assertFalse(File(context.filesDir, "isolated-pending.json").exists())
        IsolatedSourceAnalyzer(context, policy).analyze("$origin/page"); val fresh = rows().last(); proof(fresh)
        save("early", JSONObject().put("verified_asset_cancel", true).put("temporary_files", 0).put("actual_error", outcome.result.error)
            .put("python_entered", outcome.pythonEntered).put("death_confirmed", outcome.deathConfirmed).put("fresh", fresh))
    }
    @Test fun cancellationAndUiDeadlineHaveActualCleanupBeforeFreshAnalysis() = runBlocking {
        val canceled = IsolatedSourceAnalyzer(context, policy)
        val task = async(Dispatchers.Default) { canceled.analyze("$origin/stall-cancel") }
        assertNotNull(server.takeRequest(15, TimeUnit.SECONDS)); val start = SystemClock.elapsedRealtime()
        task.cancelAndJoin(); assertTrue(task.isCancelled); assertTrue(SystemClock.elapsedRealtime() - start < 1500)
        awaitResult(canceled); assertEquals(RuntimeResult.CANCELED, canceled.lastOutcome!!.result.error)
        val oldId = canceled.lastOutcome!!.result.id
        val fresh = canceled.analyze("$origin/page"); assertEquals(1, fresh.variants.size); assertFalse(fresh.variants.single().downloadAllowed)
        assertNotEquals(oldId, canceled.lastOutcome!!.result.id)
        // Remove the completed /page request so the next assertion proves the deadline request really reached TLS HTTP.
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val expired = IsolatedSourceAnalyzer(context, policy, timeoutMillis = 5000)
        val deadlineStarted = SystemClock.elapsedRealtime()
        val deadline = async(Dispatchers.Default) { runCatching { expired.analyze("$origin/stall-deadline") }.exceptionOrNull() }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)); val failure = deadline.await()
        assertTrue(failure is AnalysisFailure); assertEquals(AnalysisError.TIMEOUT, (failure as AnalysisFailure).code)
        assertTrue("Global deadline, not the transport's 3s inactivity timeout", SystemClock.elapsedRealtime() - deadlineStarted >= 4800)
        awaitResult(expired)
        // UI's deadline cancels the worker immediately; either that acknowledged cancellation or the independent
        // runtime deadline may win. Missing/WORKER_DIED/UNKNOWN/CLEANUP_FAILED is never accepted as timeout proof.
        assertTrue(expired.lastOutcome!!.result.error in setOf(RuntimeResult.CANCELED, RuntimeResult.TIMEOUT))
        expired.analyze("$origin/page"); assertEquals(0, expired.lastOutcome!!.result.error)
        save("cancel", JSONObject().put("ui_cancel", true).put("ui_deadline", true).put("actual_results", true).put("fresh_after_each", true)
            .put("proofs", JSONArray(rows().takeLast(4))))
    }
    @Test fun untrustedScopeAndWrongTlsCannotBecomeFormats() = runBlocking {
        val publicPolicy = RemoteHttpsTransport.Policy.publicOrigins(setOf("https://raw.githubusercontent.com"))
        val before = rows().size
        try { IsolatedSourceAnalyzer(context, publicPolicy).analyze("https://127.0.0.1/private") ; fail("Private scope accepted") }
        catch (failure: AnalysisFailure) { assertEquals(AnalysisError.UNSUPPORTED_SOURCE, failure.code) }
        assertEquals(before, rows().size)
        val wrong = HeldCertificate.Builder().commonName("wrong").build()
        val analyzer = IsolatedSourceAnalyzer(context, RemoteHttpsTransport.Policy.fixture(origin, wrong.certificate.encoded))
        try { analyzer.analyze("$origin/page"); fail("Untrusted TLS became metadata") }
        catch (_: AnalysisFailure) { }
        awaitResult(analyzer); assertEquals(RuntimeResult.TLS_ERROR, analyzer.lastOutcome!!.result.error)
        val rejected = rows().last()
        assertEquals(0, server.requestCount)
        IsolatedSourceAnalyzer(context, policy).analyze("$origin/page"); proof(rows().last())
        save("security", JSONObject().put("scope_refused", true).put("tls_refused", true).put("fresh", true).put("tls_runtime", rejected).put("runtime", rows().last()))
    }
}
