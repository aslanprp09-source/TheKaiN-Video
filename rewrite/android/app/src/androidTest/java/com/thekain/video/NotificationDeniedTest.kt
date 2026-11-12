package com.thekain.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** The adb host revokes permission before starting this separate instrumentation. */
@RunWith(AndroidJUnit4::class)
class NotificationDeniedTest {
    @Test fun verifiedTransferWithoutNotificationPermission() = runBlocking<Unit> {
        val fixture = M4InstrumentationTest()
        fixture.setup()
        try { fixture.activityTransfer(false) }
        finally { fixture.cleanup() }
    }
}
