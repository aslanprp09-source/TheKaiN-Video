package com.thekain.video
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thekain.video.data.DownloadTask
import com.thekain.video.domain.*
import com.thekain.video.transfer.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The actual standard APK must never downgrade a persisted isolated job, even for the allowed URL. */
@RunWith(AndroidJUnit4::class)
class IsolatedBackendRefusalTest {
    @Test fun standardApkRefusesIsolatedBackendBeforeNetworkOrStaging() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = "https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/11fa103624275958f089d9566c7722fc64133094/rewrite/android/app/src/androidTest/assets/owned-sample.mp4"
        val task = DownloadTask(UUID.randomUUID().toString(), source, "owned.mp4", "video/mp4", backend = TransferBackend.ISOLATED_OWNED)
        val stage = File(context.cacheDir, "must-not-create-${task.id}.part")
        val adapter = TransferAdapterFactory.create(context) { throw AssertionError("Forbidden direct transport was selected") }
        try {
            adapter.fetch(task, stage, { _, _, _ -> fail("Identity on refused request") }, { _, _ -> fail("Progress on refused request") })
            fail("Isolated-only task accepted by standard APK")
        } catch (failure: TransferFailure) { assertEquals(TransferError.SOURCE_POLICY, failure.code) }
        assertFalse(stage.exists())
    }
}
