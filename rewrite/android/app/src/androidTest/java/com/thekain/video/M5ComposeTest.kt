@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.thekain.video

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thekain.video.domain.TransferState
import com.thekain.video.presentation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M5ComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var state: MutableState<ScreenState>
    private var parent = Page.HOME
    private var pasted = 0
    private var submitted = 0
    private fun start(initial: ScreenState = ScreenState(), fontScale: Float? = null) {
        state = mutableStateOf(initial)
        compose.setContent {
            val systemDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(systemDensity.density, fontScale ?: systemDensity.fontScale)) {
            TheKaiNTheme(state.value.settings) {
                MainScreen(state.value, UiActions(
                    edit = { state.value = state.value.copy(home = HomeState(draft = it)) },
                    paste = { pasted++; state.value = state.value.copy(home = HomeState(draft = "https://owned.example/owned.mp4")) },
                    navigate = { page -> if (page == Page.SETTINGS) parent = state.value.page; state.value = state.value.copy(page = page) },
                    back = { state.value = state.value.copy(page = if (state.value.page == Page.SETTINGS) parent else Page.HOME) },
                    select = { state.value = state.value.copy(home = state.value.home.copy(selected = it)) },
                    download = { if (!state.value.home.enqueuePending) { submitted++; state.value = state.value.copy(home = state.value.home.copy(enqueuePending = true)) } },
                    theme = { state.value = state.value.copy(settings = state.value.settings.copy(theme = it)) },
                    language = { state.value = state.value.copy(settings = state.value.settings.copy(language = it)) }))
            }
            }
        }
    }

    @Test fun explicitPasteClearAndAccessibleActions() {
        start()
        compose.onNodeWithTag("analyze_button").assertIsNotEnabled()
        assertEquals(0, pasted)
        compose.onNodeWithTag("paste_button").performClick()
        assertEquals(1, pasted)
        compose.onNodeWithTag("source_input").assertTextContains("https://owned.example/owned.mp4")
        compose.onNodeWithTag("analyze_button").assertIsEnabled().assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
        compose.onNodeWithTag("clear_button").performClick()
        compose.onNodeWithTag("analyze_button").assertIsNotEnabled()
        compose.onNodeWithTag("clear_button").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            val density = compose.activity.resources.displayMetrics.density
            assertTrue("Touch target is shorter than 48dp: ${node.config}", node.boundsInRoot.height / density >= 47.5f)
        }
    }

    @Test fun navigationAndBackReturnToParent() {
        start()
        compose.onNodeWithTag("nav_downloads").performClick()
        compose.onNodeWithTag("task_search").assertIsDisplayed()
        compose.onNodeWithTag("settings_button").performClick()
        Espresso.pressBack()
        compose.onNodeWithTag("task_search").assertIsDisplayed()
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("library_list").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("source_input").assertIsDisplayed()
    }

    @Test fun actualFormatChoicesAndUnsupportedPauseControls() {
        start(ScreenState(home = UiFixtures.ready))
        compose.onNodeWithTag("format_selector").performScrollTo().performClick()
        compose.onNodeWithTag("variant_webm").performClick()
        compose.runOnIdle { assertEquals("webm", state.value.home.selected) }
        compose.onNodeWithTag("download_button").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(page = Page.DOWNLOADS, tasks = listOf(UiFixtures.task("owned", TransferState.DOWNLOADING))) }
        compose.onNodeWithTag("pause_owned").assertDoesNotExist()
        compose.onNodeWithTag("cancel_owned").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(tasks = listOf(UiFixtures.task("owned", TransferState.DOWNLOADING).copy(resumable = true))) }
        compose.onNodeWithTag("pause_owned").assertIsDisplayed()
    }

    @Test fun libraryExcludesUnverifiedTasksAndFiltersAreFunctional() {
        val done = UiFixtures.task("done", TransferState.COMPLETED).copy(name = "verified.mp4")
        val failed = UiFixtures.task("bad", TransferState.FAILED).copy(name = "not-verified.mp4")
        start(ScreenState(tasks = listOf(done, failed), page = Page.LIBRARY))
        compose.onNodeWithTag("task_done").assertIsDisplayed()
        compose.onNodeWithTag("task_bad").assertDoesNotExist()
        compose.onNodeWithTag("nav_downloads").performClick()
        compose.onNodeWithTag("filter_3").performScrollTo().performClick()
        compose.onNodeWithTag("task_done").assertDoesNotExist()
        compose.onNodeWithTag("task_bad").assertIsDisplayed()
    }

    @Test fun languageSwitchAndPreferencesSurviveNewSettingsInstance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UiPreferences(context)
        val old = prefs.state.value
        try {
            prefs.theme(ThemeMode.DARK); prefs.language("en")
            assertEquals(UiSettings(ThemeMode.DARK, "en"), UiPreferences(context).state.value)
            start(ScreenState(page = Page.SETTINGS))
            compose.onNodeWithTag("locale_en").performClick()
            compose.onNodeWithText("Appearance").assertIsDisplayed()
            compose.onNodeWithTag("theme_dark").performClick()
            compose.runOnIdle { assertEquals(ThemeMode.DARK, state.value.settings.theme) }
        } finally { prefs.theme(old.theme); prefs.language(old.language) }
    }

    @Test fun keyboardDoesNotCoverPrimaryActionAndCtrlEnterSubmitsOnce() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        start(ScreenState(home = UiFixtures.ready))
        compose.onNodeWithTag("source_input").performClick()
        compose.waitUntil(10_000) {
            (ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0) > 0
        }
        compose.onNodeWithTag("download_button").assertIsDisplayed()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val cta = device.wait(Until.findObject(By.res("download_button")), 5000)!!.visibleBounds
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val ime = ViewCompat.getRootWindowInsets(view)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val fullBottom = if (Build.VERSION.SDK_INT >= 30) compose.activity.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.bottom else device.displayHeight
            assertTrue("Keyboard was not displayed", ime > 0)
            assertTrue("CTA overlapped the keyboard", cta.bottom <= fullBottom - ime + 1)
        }
        compose.onRoot().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.Enter); keyUp(Key.CtrlLeft) }
        compose.runOnIdle { assertEquals(1, submitted) }
        compose.onNodeWithTag("download_button").assertIsNotEnabled()
    }


    @Test fun ctrlLReturnsHomeAndFocusesTheLinkField() {
        start(ScreenState(page = Page.DOWNLOADS))
        compose.onNodeWithTag("task_search").performClick()
        compose.onRoot().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.L); keyUp(Key.CtrlLeft) }
        compose.onNodeWithTag("source_input").assertIsFocused()
    }

    @Test fun enlargedTextRetainsVisiblePrimaryAction() {
        start(ScreenState(home = UiFixtures.ready), fontScale = 1.3f)
        compose.onNodeWithTag("download_button").assertIsDisplayed()
        compose.onNodeWithText("TheKaiN Video").assertIsDisplayed()
    }
}
