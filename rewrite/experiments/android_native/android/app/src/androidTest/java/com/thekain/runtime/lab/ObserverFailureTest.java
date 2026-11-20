package com.thekain.runtime.lab;

import android.app.ActivityManager;
import android.content.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Run LAST in a fresh instrumentation process: deliberately leaves a durable fail-closed lease. */
@RunWith(AndroidJUnit4.class)
public final class ObserverFailureTest extends RuntimeTestSupport {
    @Test public void lossOfIndependentMonitorNeverAuthorizesReuseOrClaimsKernelDeath() throws Exception {
        try(IsolatedRuntime isolated=new IsolatedRuntime(context)) {
            IsolatedRuntime.Operation running=isolated.submit(RuntimeRequest.HANG,null,1,30000);
            running.whenPythonStarted().get(25,TimeUnit.SECONDS);
            assertTrue(running.observerPid>0);assertEquals(running.peerUid,running.observerUid);
            // Real Android OS teardown of the private observer; no cooperative worker/monitor command.
            // Android keys ServiceDispatcher registrations by the binding Context.
            // IsolatedRuntime binds with Application Context, not target test Context.
            Context bindingContext=context.getApplicationContext();
            bindingContext.unbindService(running.connection);running.bound=false;
            bindingContext.stopService(new Intent(bindingContext,IsolatedRuntimeService.class));
            IsolatedRuntime.Outcome failed=running.completion().get(10,TimeUnit.SECONDS);
            assertEquals(RuntimeResult.CLEANUP_FAILED,failed.result.error);assertFalse(failed.deathConfirmed);
            assertTrue(failed.pythonEntered);assertTrue(failed.samples>0);
            // Binder proves only supervisor death. Native child PDEATHSIG is checked separately by shell;
            // the owner correctly cannot turn that fallback into a missing kernel-FD proof.
            assertNotNull(running.binder);assertFalse("OS must reap the supervisor",running.binder.isBinderAlive());
            ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            assertNotNull(manager.getRunningServices(100));
            for(ActivityManager.RunningServiceInfo service:manager.getRunningServices(100)) {
                assertNotEquals(IsolatedRuntimeService.class.getName(),service.service.getClassName());
            }
            assertTrue(new IsolatedLease(context).uncertain());
            IsolatedRuntime.Outcome refused=isolated.submit(RuntimeRequest.PROBE,null,1,30000).completion().get(2,TimeUnit.SECONDS);
            assertEquals(RuntimeResult.CLEANUP_FAILED,refused.result.error);assertFalse(refused.pythonEntered);assertEquals(0,refused.workerPid);
            try(IsolatedRuntime replacement=new IsolatedRuntime(context)) {
                IsolatedRuntime.Outcome next=replacement.submit(RuntimeRequest.PROBE,null,1,30000).completion().get(2,TimeUnit.SECONDS);
                assertEquals(RuntimeResult.CLEANUP_FAILED,next.result.error);assertEquals(0,next.workerPid);
            }
            healthyMain();
            JSONObject proof=new JSONObject().put("observer_lost",true).put("worker_pid",failed.workerPid)
                .put("supervisor_binder_dead",true).put("kernel_death_not_claimed",true).put("main_responsive",true)
                .put("durable_reuse_refused",true).put("worker_uid",failed.workerUid).put("observer_uid",failed.observerUid)
                .put("error",failed.result.error).put("production_ready",false);
            try(FileOutputStream out=context.openFileOutput("observer-failure.json",Context.MODE_PRIVATE)) {
                out.write(proof.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }
}
