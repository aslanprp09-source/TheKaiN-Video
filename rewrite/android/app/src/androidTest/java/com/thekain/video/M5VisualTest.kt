package com.thekain.video

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thekain.video.presentation.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Selected by the host after real wm size/density changes, not a synthetic Compose width override. */
@RunWith(AndroidJUnit4::class)
class M5VisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun renderMatrix() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("width_dp"))
        val width = args.getString("width_dp")!!.toInt()
        val mode = ThemeMode.valueOf(args.getString("ui_theme")!!)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(width, context.resources.configuration.screenWidthDp)
        val settings = UiSettings(mode, "ru")
        var state by mutableStateOf(ScreenState(settings = settings))
        compose.setContent { TheKaiNTheme(settings) { MainScreen(state, UiActions()) } }
        val folder = File(context.filesDir, "m5-evidence").apply { mkdirs() }
        UiFixtures.cases(settings).forEach { (name, value) ->
            compose.runOnIdle { state = value }
            compose.waitForIdle()
            if (value.page == Page.HOME) compose.onNodeWithTag(if (name == "home-ready") "download_button" else "analyze_button").assertIsDisplayed()
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            assertEquals(width, image.width)
            File(folder, "$width-${mode.name.lowercase()}-$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
