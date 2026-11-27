package com.thekain.video

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.thekain.video.data.*
import com.thekain.video.domain.*
import com.thekain.video.presentation.MainViewModel
import com.thekain.video.source.*
import com.thekain.video.storage.MediaVerifier
import com.thekain.video.transfer.TransferAdapterFactory
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/** Independent media oracle; actions use real MainActivity/buttons, not an injected analyzer/transport. */
@RunWith(AndroidJUnit4::class)
class OwnedFormatsUiTest {
    private lateinit var activity: MainActivity
    private val activityRule = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(activityRule).around(UiWindowFailureRule { activity })
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun visible(selector: BySelector): UiObject2 {
        for (attempt in 0..6) {
            device.findObject(selector)?.let {
                // A clipped sliver under a fixed footer is not an actionable 48dp touch target.
                if (it.visibleBounds.width() > 0 && it.visibleBounds.height() >= (48 * activity.resources.displayMetrics.density).toInt()) return it
            }
            if (attempt < 6) requireNotNull(device.findObject(By.res("home_content"))).scroll(Direction.DOWN, .7f)
        }
        throw AssertionError("Actual format selector was not visible")
    }

    @Test fun exactOwnedGrantsDoNotAuthorizeAliasesQueriesOrAnotherRepositoryPath() = runBlocking<Unit> {
        activityRule.scenario.onActivity { activity = it }
        val context = instrumentation.targetContext
        val adapter = TransferAdapterFactory.create(context) { throw AssertionError("Refused isolated job reached direct connections") }
        assertTrue(SourceAnalyzerFactory.permitsOwnedVideo(SourceAnalyzerFactory.ownedVideo))
        assertTrue(SourceAnalyzerFactory.permitsOwnedVideo(SourceAnalyzerFactory.ownedVideo320))
        val denied = listOf(SourceAnalyzerFactory.ownedVideo320 + "?alternate=1",
            SourceAnalyzerFactory.ownedVideo320.replace("/5e3b70b75a3d70780e49e24ddbf64a10356f2c09/", "/rebuild-2026-11-11/"),
            SourceAnalyzerFactory.ownedVideo320.replace("/TheKaiN-Video/", "/other/"),
            SourceAnalyzerFactory.ownedVideo320.replace("raw.githubusercontent.com/", "raw.githubusercontent.com:443/"))
        for (url in denied) {
            assertFalse(SourceAnalyzerFactory.permitsOwnedVideo(url))
            val stage = File(context.cacheDir, "refused-format-${java.util.UUID.randomUUID()}.part")
            try {
                adapter.fetch(DownloadTask(java.util.UUID.randomUUID().toString(), url, "refused.mp4", "video/mp4", backend = TransferBackend.ISOLATED_OWNED),
                    stage, { _, _, _ -> fail("Refused grant reported identity") }, { _, _ -> fail("Refused grant reported progress") })
                fail("An ungranted alias was transferred")
            } catch (failure: TransferFailure) { assertEquals(TransferError.SOURCE_POLICY, failure.code) }
            assertFalse(stage.exists())
        }
        val folder = File(context.filesDir, "main-formats-evidence").apply { mkdirs() }
        folder.resolve("grant.json").writeText(JSONObject().put("source_commit", BuildConfig.INTERNAL_RUNTIME_SOURCE)
            .put("api", android.os.Build.VERSION.SDK_INT).put("pages", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
            .put("exact_grant_refusals", denied.size).put("no_network_or_staging", true).put("production_ready", false).toString())
    }

    @Test fun selectEachRealFormatDownloadsItsOwnVerifiedAudioVideoAndRetainsHistory() = runBlocking<Unit> {
        activityRule.scenario.onActivity { activity = it }
        val graph = (activity.application as TheKaiNApp).graph
        graph.recoveryReady.await()
        val before = graph.repository.all().map { it.id }.toSet()
        val owned = mutableListOf<DownloadTask>()
        val evidence = JSONArray()
        try {
            UiWindowEvidence.await(activity, "formats-input")
            requireNotNull(device.wait(Until.findObject(By.res("source_input")), 10_000)).text = SourceAnalyzerFactory.ownedFormatsPage
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            UiWindowEvidence.await(activity, "formats-analyze")
            requireNotNull(device.findObject(By.res("analyze_button"))).click()
            assertNotNull("Real two-format analysis did not reach READY", device.wait(Until.findObject(By.res("download_button")), 35_000))
            var variants: List<MediaVariant> = emptyList()
            instrumentation.runOnMainSync { variants = ViewModelProvider(activity)[MainViewModel::class.java].home.state.value.source!!.variants }
            assertEquals(2, variants.size)
            assertEquals(setOf(90, 180), variants.map { it.height }.toSet())
            assertEquals(2, variants.map { it.id }.toSet().size)
            variants.forEach {
                assertTrue(it.downloadAllowed); assertEquals(TransferBackend.ISOLATED_OWNED, it.request.backend)
                assertNull(it.bytes); assertNull(it.fps); assertNull(it.videoCodec) // Pinned extractor drops HTML video codec; don't invent it.
                assertEquals("mp4a.40.2", it.audioCodec)
            }
            val runtime = JSONObject(File(activity.filesDir, "isolated-source-evidence.jsonl").readLines().last { it.isNotBlank() })
            assertEquals(0, runtime.getInt("error")); assertEquals(2, runtime.getInt("formats"))
            assertEquals(1L, runtime.getLong("python_calls")); assertFalse(runtime.getBoolean("download_enabled"))
            val folder = File(activity.filesDir, "main-formats-evidence").apply { mkdirs() }
            // Select highest first, then the other actual candidate. Neither click may just reuse the default/previous request.
            for ((index, height) in listOf(180, 90).withIndex()) {
                val variant = variants.single { it.height == height }
                val width = if (height == 180) 320 else 160
                val expectedBytes = if (height == 180) 2485L else 2428L
                val expectedHash = if (height == 180) "eec943f4eb96f892f1b5f2cce512d4802f810b38739d2ccfad49d1eb437e0856"
                    else "dd201a3b814b113ca98749902195b996a1e2f608247bdd22d2044b4c0dfd5cb2"
                assertEquals(width, variant.width)
                UiWindowEvidence.await(activity, "formats-choice-$height")
                val selector = visible(By.res("format_selector"))
                assertTrue(selector.isEnabled); selector.click()
                assertNotNull("Actual format popup did not open", device.wait(Until.findObject(By.res("format_options")), 5000))
                for (candidate in variants) {
                    val item = requireNotNull(device.wait(Until.findObject(By.res("variant_${candidate.id}")), 5000))
                    val label = "${candidate.width} × ${candidate.height}"
                    assertTrue(item.text.orEmpty().contains(label) || item.findObject(By.textContains(label)) != null)
                }
                val high = requireNotNull(device.findObject(By.res("variant_${variants.single { it.height == 180 }.id}")))
                val low = requireNotNull(device.findObject(By.res("variant_${variants.single { it.height == 90 }.id}")))
                assertTrue("Known quality choices are not in descending order", high.visibleBounds.top < low.visibleBounds.top)
                assertTrue(device.takeScreenshot(folder.resolve("choices-$height.png")))
                requireNotNull(device.findObject(By.res("variant_${variant.id}"))).click()
                assertTrue(device.wait(Until.gone(By.res("variant_${variant.id}")), 5000))
                val selected = requireNotNull(device.findObject(By.res("format_selector")))
                assertTrue(selected.text.orEmpty().contains("$width × $height") || selected.findObject(By.textContains("$width × $height")) != null)
                instrumentation.runOnMainSync {
                    assertEquals(variant.id, ViewModelProvider(activity)[MainViewModel::class.java].home.state.value.selected)
                }
                val download = requireNotNull(device.findObject(By.res("download_button")))
                assertTrue(download.isEnabled); download.click()
                val completed = withTimeout(90_000) {
                    while (true) {
                        val fresh = graph.repository.all().filter { it.id !in before }
                        assertTrue("Duplicate row from format click", fresh.size <= index + 1)
                        fresh.singleOrNull { it.id !in owned.map { old -> old.id } }?.let { task ->
                            assertEquals(variant.request.url, task.url)
                            assertEquals(TransferBackend.ISOLATED_OWNED, task.backend)
                            assertNotEquals("Selected transfer failed: ${task.error}", TransferState.FAILED, task.state)
                            if (task.state == TransferState.COMPLETED) return@withTimeout task
                        }
                        delay(50)
                    }
                    @Suppress("UNREACHABLE_CODE") error("Unreachable")
                }
                owned += completed
                assertEquals(expectedBytes, completed.verifiedBytes); assertEquals(expectedHash, completed.digest)
                assertEquals(100, completed.percent); assertFalse(completed.resumable)
                val uri = Uri.parse(completed.outputUri)
                val digest = requireNotNull(activity.contentResolver.openInputStream(uri)).use { MediaVerifier.digest(it) }
                assertEquals(expectedBytes, digest.bytes); assertEquals(expectedHash, digest.sha256)
                val tracks = verifyTracks(uri, width, height)
                val player = MediaPlayer()
                try {
                    player.setDataSource(activity, uri); player.prepare()
                    assertEquals(width, player.videoWidth); assertEquals(height, player.videoHeight)
                    assertTrue(player.duration > 0); player.start()
                    withTimeout(10_000) { while (player.currentPosition == 0) delay(20) }
                } finally { player.release() }
                val receipt = JSONObject(File(activity.filesDir, "isolated-transfer-evidence.jsonl").readLines().last { it.isNotBlank() })
                assertTrue(receipt.getBoolean("transfer_receipt")); assertTrue(receipt.getBoolean("kernel_process_gone"))
                evidence.put(JSONObject().put("job_id", completed.id).put("format_id", variant.id)
                    .put("width", width).put("height", height).put("verified_bytes", digest.bytes).put("sha256", digest.sha256)
                    .put("audio_tracks", tracks.second).put("video_tracks", tracks.first).put("all_tracks_sampled", true)
                    .put("selected_url_persisted", true).put("playback", true).put("proof", receipt))
            }
            assertNotEquals(owned[0].outputUri, owned[1].outputUri); assertNotEquals(owned[0].digest, owned[1].digest)
            activityRule.scenario.recreate(); activityRule.scenario.onActivity { activity = it }
            UiWindowEvidence.await(activity, "formats-history")
            val reopened = Room.databaseBuilder(activity, DownloadsDb::class.java, "downloads.db").addMigrations(DownloadsDb.MIGRATION_1_2).build()
            try { owned.forEach { assertEquals(it, DownloadRepository(reopened).get(it.id)) } } finally { reopened.close() }
            requireNotNull(device.wait(Until.findObject(By.res("nav_downloads")), 10_000)).click()
            owned.forEach { assertTrue(device.wait(Until.hasObject(By.res("open_${it.id}")), 10_000)) }
            assertTrue(device.takeScreenshot(folder.resolve("history.png")))
            folder.resolve("ui.json").writeText(JSONObject().put("source_commit", BuildConfig.INTERNAL_RUNTIME_SOURCE)
                .put("api", android.os.Build.VERSION.SDK_INT).put("pages", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
                .put("actual_main_ui", true).put("unknown_size_fps_video_codec", true).put("distinct_quality_labels", true).put("descending_choices", true)
                .put("durable_history", true).put("open_actions", true).put("distinct_outputs", true).put("runtime", runtime).put("selections", evidence)
                .put("production_ready", false).toString())
        } catch (failure: Throwable) {
            // Capture before worker/file cleanup can change the failing UI. Owned disposable-emulator content only.
            runCatching {
                val out = java.io.ByteArrayOutputStream()
                device.dumpWindowHierarchy(out)
                val safe = out.toString("UTF-8").replace(Regex("https?://[^\\s\\\"<>]+"), "OWNED_URL_REDACTED").take(48 * 1024)
                val folder = File(activity.filesDir, "main-formats-evidence").apply { mkdirs() }
                folder.resolve("failure-ui.xml").writeText(safe)
                device.takeScreenshot(folder.resolve("failure.png"))
                println("FORMAT_UI_FAILURE_HIERARCHY $safe")
            }
            throw failure
        } finally {
            // Only disposable-emulator rows created by this test; never reset the database or user's app.
            graph.repository.all().filter { it.id !in before }.forEach { task ->
                withTimeout(30_000) {
                    while (androidx.work.WorkManager.getInstance(activity).getWorkInfosForUniqueWork("download-${task.id}").get().any { !it.state.isFinished }) delay(50)
                }
                if (task.state == TransferState.COMPLETED) graph.removeCompleted(task.id, removeFile = true)
            }
        }
    }

    private fun verifyTracks(uri: Uri, width: Int, height: Int): Pair<Int, Int> {
        val extractor = MediaExtractor()
        try {
            activity.contentResolver.openFileDescriptor(uri, "r").use { fd -> extractor.setDataSource(requireNotNull(fd).fileDescriptor) }
            val video = mutableListOf<Int>(); val audio = mutableListOf<Int>()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                when (format.getString(MediaFormat.KEY_MIME)) {
                    "video/avc" -> { assertEquals(width, format.getInteger(MediaFormat.KEY_WIDTH)); assertEquals(height, format.getInteger(MediaFormat.KEY_HEIGHT)); video += index }
                    "audio/mp4a-latm" -> audio += index
                    else -> fail("Unexpected media track")
                }
                extractor.selectTrack(index)
            }
            assertEquals(1, video.size); assertEquals(1, audio.size)
            val sampled = mutableSetOf<Int>(); val bytes = ByteBuffer.allocate(64 * 1024)
            while (extractor.sampleTrackIndex >= 0) {
                sampled += extractor.sampleTrackIndex; bytes.clear()
                assertTrue(extractor.readSampleData(bytes, 0) > 0)
                if (!extractor.advance()) break
            }
            assertEquals((video + audio).toSet(), sampled)
            return video.size to audio.size
        } finally { extractor.release() }
    }
}
