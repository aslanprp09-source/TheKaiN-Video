package com.thekain.video

import android.media.MediaPlayer
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.thekain.video.data.*
import com.thekain.video.domain.*
import com.thekain.video.source.SourceAnalyzerFactory
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/** The existing app UI, real isolated Python, public system TLS, actual transfer, verification and playback. */
@RunWith(AndroidJUnit4::class)
class OwnedProgressiveUiTest {
    private lateinit var activity: MainActivity
    private val activityRule = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(activityRule).around(UiWindowFailureRule { activity })
    @Test fun realHomeDownloadsPlaysAndRetainsOwnedIsolatedFormat() = runBlocking<Unit> {
        activityRule.scenario.onActivity { activity = it }
        val graph = (activity.application as TheKaiNApp).graph
        graph.recoveryReady.await()
        val existing = graph.repository.all().map { it.id }.toSet()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var id: String? = null
        try {
            UiWindowEvidence.await(activity, "progressive-source")
            requireNotNull(device.wait(Until.findObject(By.res("source_input")), 10_000)).text = SourceAnalyzerFactory.ownedDownloadPage
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            UiWindowEvidence.await(activity, "progressive-analyze")
            requireNotNull(device.findObject(By.res("analyze_button"))).click()
            val download = requireNotNull(device.wait(Until.findObject(By.res("download_button")), 35_000))
            assertTrue("Explicitly accepted owned MP4 never became downloadable", download.isEnabled)
            val runtime = JSONObject(File(activity.filesDir, "isolated-source-evidence.jsonl").readLines().last { it.isNotBlank() })
            assertEquals(0, runtime.getInt("error")); assertTrue(runtime.getBoolean("death_confirmed"))
            assertFalse(runtime.getBoolean("download_enabled")) // Python cannot grant permission; only the owner does.
            UiWindowEvidence.await(activity, "progressive-download")
            download.click()
            val completed = withTimeout(90_000) {
                while (true) {
                    val rows = graph.repository.all().filter { it.id !in existing }
                    assertTrue("A single click created duplicate rows", rows.size <= 1)
                    rows.singleOrNull()?.let { task ->
                        id = task.id
                        assertNotEquals("Isolated transfer failed: ${task.error}", TransferState.FAILED, task.state)
                        if (task.state == TransferState.COMPLETED) return@withTimeout task
                    }
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE") error("Unreachable")
            }
            assertEquals(TransferBackend.ISOLATED_OWNED, completed.backend)
            assertEquals(2428L, completed.verifiedBytes)
            assertEquals("dd201a3b814b113ca98749902195b996a1e2f608247bdd22d2044b4c0dfd5cb2", completed.digest)
            assertFalse(completed.resumable); assertEquals(100, completed.percent)
            val receipt = JSONObject(File(activity.filesDir, "isolated-transfer-evidence.jsonl").readLines().last { it.isNotBlank() })
            assertTrue(receipt.getBoolean("transfer_only")); assertTrue(receipt.getBoolean("transfer_receipt"))
            assertTrue(receipt.getBoolean("terminated")); assertTrue(receipt.getBoolean("kernel_process_gone")); assertTrue(receipt.getBoolean("lease_clean"))
            assertFalse(receipt.getBoolean("owner_python_loaded"))
            val player = MediaPlayer()
            try {
                player.setDataSource(activity, Uri.parse(completed.outputUri)); player.prepare()
                assertTrue(player.duration > 0); player.start()
                withTimeout(10_000) { while (player.currentPosition == 0) delay(20) }
            } finally { player.release() }
            activityRule.scenario.recreate(); activityRule.scenario.onActivity { activity = it }
            UiWindowEvidence.await(activity, "progressive-history")
            val reopened = Room.databaseBuilder(activity, DownloadsDb::class.java, "downloads.db").addMigrations(DownloadsDb.MIGRATION_1_2).build()
            try { assertEquals(completed, DownloadRepository(reopened).get(completed.id)) } finally { reopened.close() }
            requireNotNull(device.wait(Until.findObject(By.res("nav_downloads")), 10_000)).click()
            assertTrue(device.wait(Until.hasObject(By.res("task_${completed.id}")), 10_000))
            assertTrue(device.wait(Until.hasObject(By.res("open_${completed.id}")), 10_000))
            val folder = File(activity.filesDir, "main-progressive-evidence").apply { mkdirs() }
            assertTrue(device.takeScreenshot(folder.resolve("completed.png")))
            folder.resolve("ui.json").writeText(JSONObject().put("case", "ui").put("source_commit", BuildConfig.INTERNAL_RUNTIME_SOURCE)
                .put("api", android.os.Build.VERSION.SDK_INT).put("pages", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
                .put("actual_main_ui", true).put("isolated_python", runtime).put("proofs", org.json.JSONArray().put(receipt))
                .put("verified_bytes", completed.verifiedBytes).put("sha256", completed.digest).put("playback", true)
                .put("durable_history", true).put("open_action", true).put("production_ready", false).toString())
        } finally {
            id?.let { own ->
                withTimeout(30_000) {
                    while (androidx.work.WorkManager.getInstance(activity).getWorkInfosForUniqueWork("download-$own").get().any { !it.state.isFinished }) delay(50)
                }
                if (graph.repository.get(own).state == TransferState.COMPLETED) graph.removeCompleted(own, removeFile = true)
            }
        }
    }
}
