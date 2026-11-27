package com.thekain.video
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.thekain.video.presentation.MainViewModel
import com.thekain.video.presentation.localized
import com.thekain.video.source.SourceAnalyzerFactory
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/** Real MainActivity/buttons and real-clock networking. Lazy content must be scrolled into view. */
@RunWith(AndroidJUnit4::class)
class OwnedIsolatedUiTest {
    private lateinit var activity: MainActivity
    private val activityRule = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(activityRule).around(UiWindowFailureRule { activity })
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun visibleInHome(selector: BySelector): UiObject2 {
        for (attempt in 0..6) {
            val found = device.findObject(selector)
            if (found != null && found.visibleBounds.width() > 0 && found.visibleBounds.height() > 0) return found
            if (attempt == 6) break
            requireNotNull(device.findObject(By.res("home_content"))).scroll(Direction.DOWN, .7f)
            device.wait(Until.hasObject(selector), 500)
        }
        throw AssertionError("Expected actual visible source detail after scrolling home")
    }
    @Test fun realHomeAnalyzesOwnedPageAndKeepsUncheckedDownloadDisabled() {
        activityRule.scenario.onActivity { activity = it }
        try {
            UiWindowEvidence.await(activity, "isolated-owned-page")
            val input = requireNotNull(device.wait(Until.findObject(By.res("source_input")), 10_000))
            input.text = SourceAnalyzerFactory.ownedPage
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            UiWindowEvidence.await(activity, "isolated-analyze-action")
            requireNotNull(device.findObject(By.res("analyze_button"))).click()
            // The fixed footer reflects READY even when the lazy preview is below the viewport.
            val download = device.wait(Until.findObject(By.res("download_button")), 35_000)
            assertNotNull("Actual main UI did not reach READY", download)
            assertFalse(requireNotNull(download).isEnabled)
            visibleInHome(By.res("source_preview"))
            val unavailable = activity.localized().getString(R.string.format_download_unavailable)
            assertNotNull(visibleInHome(By.text(unavailable)))
            assertFalse(requireNotNull(device.findObject(By.res("download_button"))).isEnabled)
            val app = activity.application as TheKaiNApp
            val file = File(app.filesDir, "isolated-source-evidence.jsonl")
            val runtime = JSONObject(file.readLines().last { it.isNotBlank() })
            assertEquals(0, runtime.getInt("error")); assertTrue(runtime.getBoolean("death_confirmed"))
            val folder = File(app.filesDir, "main-isolated-evidence").apply { mkdirs() }
            assertTrue(device.takeScreenshot(File(folder, "preview.png")))
            folder.resolve("ui.json").writeText(JSONObject()
                .put("case", "ui").put("source_commit", BuildConfig.INTERNAL_RUNTIME_SOURCE).put("api", android.os.Build.VERSION.SDK_INT)
                .put("pages", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)).put("actual_main_ui", true)
                .put("download_disabled", true).put("runtime", runtime).put("production_ready", false).toString())
        } catch (failure: Throwable) {
            // Fixed typed diagnostics; never print the input URL, title or arbitrary exception message.
            runCatching {
                var status = "UNAVAILABLE"
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val state = ViewModelProvider(activity)[MainViewModel::class.java].home.state.value
                    status = "phase=${state.phase};error=${state.analysisError};formats=${state.source?.variants?.size ?: 0}"
                }
                println("MAIN_SOURCE_UI_FAILURE $status")
                val journal = File(activity.filesDir, "isolated-source-evidence.jsonl")
                if (journal.exists()) println("MAIN_SOURCE_LAST_PROOF " + journal.readLines().last { it.isNotBlank() })
            }
            throw failure
        }
    }
}
