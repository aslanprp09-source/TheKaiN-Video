package com.thekain.video

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import org.junit.runners.model.Statement
import java.io.File

/** Test-only state gate; never skips/retries a test or dismisses app/permission dialogs. */
internal object UiWindowEvidence {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val root get() = File(instrumentation.targetContext.filesDir, "ui-failure-evidence").apply { mkdirs() }

    private data class WindowState(val ready: Boolean, val text: String)
    private fun state(activity: Activity): WindowState {
        var ready = false
        var text = ""
        instrumentation.runOnMainSync {
            val view = activity.window.decorView
            val stage = ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity)
            val keyguard = activity.getSystemService(KeyguardManager::class.java)
            ready = stage == Stage.RESUMED && !activity.isFinishing && !activity.isDestroyed &&
                activity.hasWindowFocus() && view.isAttachedToWindow && view.isShown &&
                view.width > 0 && view.height > 0 && !keyguard.isKeyguardLocked
            text = "component=${activity.componentName.flattenToString()}\n" +
                "stage=$stage\nhasWindowFocus=${activity.hasWindowFocus()}\n" +
                "viewAttached=${view.isAttachedToWindow}\nviewShown=${view.isShown}\n" +
                "viewSize=${view.width}x${view.height}\nkeyguardLocked=${keyguard.isKeyguardLocked}\n" +
                "activityCurrentFocus=${activity.currentFocus?.javaClass?.name}\n"
        }
        val activePackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
        ready = ready && activePackage == activity.packageName
        return WindowState(ready, text + "activeWindowPackage=$activePackage\nready=$ready\n")
    }

    fun await(activity: Activity, label: String) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val deadline = SystemClock.uptimeMillis() + 10_000
        var consecutive = 0
        var last = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val current = state(activity)
            last = current.text
            consecutive = if (current.ready) consecutive + 1 else 0
            if (consecutive == 2) {
                File(root, "ready-${safeName(label)}.txt").writeText(last)
                return
            }
            SystemClock.sleep(100) // Instrumentation thread only, never the UI thread.
        }
        val blocker = blockingWindowSummary()
        capture("not-ready-$label", activity)
        throw AssertionError("Expected RESUMED, focused, visible unlocked app window before $label:\n$last$blocker")
    }

    /** Preserve the actual system blocker in JUnit XML even if the app is later removed. */
    private fun blockingWindowSummary(): String = runCatching {
        val active = instrumentation.uiAutomation.rootInActiveWindow
        val nodes = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        if (active != null) nodes.add(active)
        val lines = mutableListOf<String>()
        var visited = 0
        while (nodes.isNotEmpty() && visited++ < 32) {
            val node = nodes.removeFirst()
            val system = node.packageName?.toString() in setOf("android", "com.android.systemui")
            if (system) lines.add("blockingNode=${node.viewIdResourceName}: ${node.text?.take(160)} ${node.contentDescription?.take(160)}")
            for (index in 0 until minOf(node.childCount, 16)) node.getChild(index)?.let(nodes::add)
        }
        val descriptor = instrumentation.uiAutomation.executeShellCommand("timeout 2 dumpsys window windows")
        val dump = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() < 512 * 1024) {
                val count = input.read(buffer, 0, minOf(buffer.size, 512 * 1024 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
        lines.addAll(dump.lineSequence().filter { "mCurrentFocus=" in it || "mFocusedApp=" in it }.take(8))
        lines.joinToString("\n", postfix = "\n").take(6000)
    }.getOrElse { "blockingWindowCaptureError=${it.javaClass.name}\n" }

    private fun safeName(value: String) = value.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(140)

    /** Capture while the failing Activity is still alive; errors never replace the original failure. */
    fun capture(label: String, activity: Activity? = null) {
        try {
        val folder = File(root, "${SystemClock.uptimeMillis()}-${safeName(label)}").apply { mkdirs() }
        fun save(name: String, block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                runCatching { File(folder, "capture-errors.txt").appendText("$name: ${error.javaClass.name}\n") }
            }
        }
        save("activity") {
            val snapshot = activity?.let { state(it).text } ?: "activityReference=absent\n"
            File(folder, "activity-state.txt").writeText("label=$label\nsdk=${Build.VERSION.SDK_INT}\n$snapshot")
        }
        save("screenshot") { check(device.takeScreenshot(File(folder, "screen.png"))) }
        for ((name, command) in mapOf(
            "window.txt" to "timeout 5 dumpsys window",
            "activities.txt" to "timeout 5 dumpsys activity activities",
            "last-anr.txt" to "timeout 5 dumpsys activity lastanr",
            "power.txt" to "timeout 5 dumpsys power"
        )) save(name) {
            // Fixed read-only shell commands; no URL, test data, or user input is interpolated.
            val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                File(folder, name).outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var remaining = 1024 * 1024
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count < 0) break
                        output.write(buffer, 0, count); remaining -= count
                    }
                }
            }
        }
        save("hierarchy") { device.dumpWindowHierarchy(File(folder, "hierarchy.xml")) }
        } catch (error: Throwable) {
            android.util.Log.e("UiWindowEvidence", "Diagnostic collection unavailable: ${error.javaClass.name}")
        }
    }
}

/** Must be INSIDE the Activity/Compose rule so capture precedes Activity teardown. */
internal class UiWindowFailureRule(private val activity: () -> Activity) : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            try { base.evaluate() } catch (failure: Throwable) {
                UiWindowEvidence.capture(description.displayName, runCatching { activity() }.getOrNull())
                throw failure
            }
        }
    }
}

/** Backup for non-UI assertions/runner failures; selected UI tests also capture before teardown. */
class UiFailureListener : RunListener() {
    override fun testFailure(failure: Failure) {
        UiWindowEvidence.capture("listener-${failure.description.displayName}")
    }
}
