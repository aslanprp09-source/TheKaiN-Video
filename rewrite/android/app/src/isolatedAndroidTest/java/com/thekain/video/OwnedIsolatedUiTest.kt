package com.thekain.video
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thekain.video.source.SourceAnalyzerFactory
import org.json.JSONObject
import org.junit.*
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/** Real main UI, not renderer fixtures; no media transfer is authorized by this stage. */
@RunWith(AndroidJUnit4::class)
class OwnedIsolatedUiTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(compose).around(UiWindowFailureRule { compose.activity })
    @Test fun realHomeAnalyzesOwnedPageAndKeepsUncheckedDownloadDisabled() {
        UiWindowEvidence.await(compose.activity, "isolated-owned-page")
        compose.onNodeWithTag("url_input").performTextReplacement(SourceAnalyzerFactory.ownedPage)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        UiWindowEvidence.await(compose.activity, "isolated-analyze-action")
        compose.onNodeWithTag("analyze_button").performClick()
        compose.waitUntil(35_000) { compose.onAllNodesWithTag("source_preview").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("source_preview").assertExists()
        compose.onNodeWithTag("download_button").assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.format_download_unavailable)).assertExists()
        val app = compose.activity.application as TheKaiNApp
        val file = File(app.filesDir, "isolated-source-evidence.jsonl")
        val runtime = JSONObject(file.readLines().last { it.isNotBlank() })
        org.junit.Assert.assertEquals(0, runtime.getInt("error")); org.junit.Assert.assertTrue(runtime.getBoolean("death_confirmed"))
        val folder = File(app.filesDir, "main-isolated-evidence").apply { mkdirs() }
        org.junit.Assert.assertTrue(androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(folder, "preview.png")))
        folder.resolve("ui.json").writeText(JSONObject()
            .put("case", "ui").put("source_commit", BuildConfig.INTERNAL_RUNTIME_SOURCE).put("api", android.os.Build.VERSION.SDK_INT)
            .put("pages", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)).put("actual_main_ui", true)
            .put("download_disabled", true).put("runtime", runtime).put("production_ready", false).toString())
    }
}
