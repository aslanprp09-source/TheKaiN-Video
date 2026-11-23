package com.thekain.runtime.lab;

import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Fixed external CI driver kills only the normal launcher owner before this fresh process. */
@RunWith(AndroidJUnit4.class)
public final class OwnerRecoveryTest extends RuntimeTestSupport {
    @Test public void abruptOwnerDeathRecoversFromDurableKernelGuardWithoutReboot() throws Exception {
        assertTrue(new IsolatedLease(context).uncertain());
        try(IsolatedRuntime runtime=new IsolatedRuntime(context)) {
            IsolatedRuntime.Outcome blocked=runtime.submit(RuntimeRequest.PROBE,null,1,30000).completion().get(2,TimeUnit.SECONDS);
            assertEquals(RuntimeResult.CLEANUP_FAILED,blocked.result.error);assertEquals(0,blocked.workerPid);
            IsolatedRuntime.RecoveryOutcome proof=runtime.recoverInterruptedWorker().get(4,TimeUnit.SECONDS);
            assertEquals(IsolatedLease.RECOVERED,proof.status);assertNotEquals(parentPid,proof.previousOwnerPid);assertTrue(proof.inode>0);
            IsolatedRuntime.Outcome next=runtime.submit(RuntimeRequest.ANALYZE,url,1,30000).completion().get(35,TimeUnit.SECONDS);
            assertEquals(0,next.result.error);assertTrue(next.deathConfirmed);assertTrue(next.ownerCanaryIntact);
            assertNotEquals(android.os.Process.myUid(),next.workerUid);healthyMain();
        }
    }
}
