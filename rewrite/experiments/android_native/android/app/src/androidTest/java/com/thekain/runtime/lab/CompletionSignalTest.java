package com.thekain.runtime.lab;

import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.FileOutputStream;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real isolated worker, deliberately queued notifications; original65 tests are unchanged. */
@RunWith(AndroidJUnit4.class)
public final class CompletionSignalTest extends RuntimeTestSupport {
    @Test public void repeatedForgedProcRejectionCannotBecomeWorkerDiedDuringChannelClose() throws Exception {
        JSONArray rows=new JSONArray();
        try(IsolatedRuntime isolated=new IsolatedRuntime(context)) {
            for(int attempt=0;attempt<5;attempt++) {
                IsolatedRuntime.Outcome rejected=isolated.submit(RuntimeRequest.PROBE,null,1,30000,
                    RuntimeRequest.MAX_RSS,IsolatedInput.FAKE_PROC).outcomeSignal().get(35,TimeUnit.SECONDS);
                assertEquals(RuntimeResult.IPC_ERROR,rejected.result.error);
                assertFalse(rejected.pythonEntered);assertTrue(rejected.deathConfirmed);assertTrue(rejected.ownerCanaryIntact);
                IsolatedRuntime.Outcome fresh=isolated.submit(RuntimeRequest.ANALYZE,url,1,30000).outcomeSignal().get(35,TimeUnit.SECONDS);
                assertEquals(RuntimeResult.OK,fresh.result.error);assertTrue(fresh.deathConfirmed);assertTrue(fresh.ownerCanaryIntact);
                assertNotEquals(rejected.result.id,fresh.result.id);healthyMain();
                java.io.File[] files=context.getCacheDir().listFiles((folder,name)->name.startsWith("isolated-python-") || name.startsWith("isolated-fixture-"));
                assertNotNull(files);assertEquals(0,files.length);
                rows.put(new JSONObject().put("rejected_id",rejected.result.id).put("fresh_id",fresh.result.id)
                    .put("error",rejected.result.error).put("python_entered",rejected.pythonEntered)
                    .put("death_confirmed",rejected.deathConfirmed).put("owner_canary_intact",rejected.ownerCanaryIntact)
                    .put("fresh_error",fresh.result.error).put("fresh_death_confirmed",fresh.deathConfirmed)
                    .put("fresh_owner_canary_intact",fresh.ownerCanaryIntact).put("owned_ipc_temp_files",files.length));
            }
        }
        try(FileOutputStream out=context.openFileOutput("forged-proc-rejection-evidence.json",0)) {
            out.write(new JSONObject().put("source_commit",BuildConfig.QA_SOURCE_COMMIT).put("rows",rows)
                .put("api",android.os.Build.VERSION.SDK_INT).put("page_size",android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
                .put("production_ready",false).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    @Test public void blockedNotificationsCannotDelayRealCancellationDeadlineOrFreshAnalysis() throws Exception {
        ArrayBlockingQueue<Runnable> notifications=new ArrayBlockingQueue<>(16);
        Executor paused=task->{if(!notifications.offer(task))throw new RejectedExecutionException("TEST_NOTIFICATION_LIMIT");};
        JSONArray rows=new JSONArray();
        IsolatedRuntime.Operation active=null;
        try(IsolatedRuntime isolated=new IsolatedRuntime(context,null,paused)) {
            try {
                for(int step=0;step<3;step++) {
                    int kind=step==2?RuntimeRequest.ANALYZE:RuntimeRequest.HANG;
                    active=isolated.submit(kind,step==2?url:null,1,step==1?12000:30000,RuntimeRequest.MAX_RSS,
                        step==2?IsolatedInput.NORMAL:IsolatedInput.BLOCK_MAIN);
                    CompletableFuture<IsolatedRuntime.Outcome> notification=active.completion();
                    CompletableFuture<Integer> startNotification=active.whenPythonStarted();
                    Future<IsolatedRuntime.Outcome> signal=active.outcomeSignal();
                    assertFalse(signal instanceof CompletableFuture);
                    assertFalse("A signal cannot cancel the underlying worker",signal.cancel(true));
                    assertTrue(active.pythonStartedSignal().get(25,TimeUnit.SECONDS)>0);
                    assertFalse("The notification executor was not actually paused",startNotification.isDone());
                    if(step==0)assertTrue(active.cancel());
                    IsolatedRuntime.Outcome out=QaWait.await(signal,35,(status,awaited,until)->{});
                    assertEquals(step==0?RuntimeResult.CANCELED:step==1?RuntimeResult.TIMEOUT:RuntimeResult.OK,out.result.error);
                    assertTrue(out.deathConfirmed);assertTrue(out.ownerCanaryIntact);assertEquals(255,out.childProtection);
                    assertFalse(out.observerPythonLoaded);assertNotEquals(parentPid,out.workerPid);
                    assertNotEquals(android.os.Process.myUid(),out.workerUid);healthyMain();
                    if(step==0)assertTrue(out.cancelToExitMs>=0 && out.cancelToExitMs<=3000);
                    if(step==1)assertTrue(out.elapsedMs>=12000 && out.pythonEntered);
                    if(step==2) {assertEquals(1,out.result.formats);assertTrue(out.requests>0);}
                    assertFalse("Published result must remain independent of delayed async copy",notification.isDone());
                    java.io.File[] temporary=context.getCacheDir().listFiles((folder,name)->name.startsWith("isolated-python-") || name.startsWith("isolated-fixture-"));
                    assertNotNull(temporary);assertEquals(0,temporary.length);
                    rows.put(new JSONObject().put("id",out.result.id).put("step",step).put("error",out.result.error)
                        .put("death_confirmed",out.deathConfirmed).put("owner_canary_intact",out.ownerCanaryIntact)
                        .put("child_protection",out.childProtection).put("worker_uid",out.workerUid)
                        .put("owner_uid",android.os.Process.myUid()).put("signal_done",signal.isDone())
                        .put("notification_done_before_release",notification.isDone()).put("owned_ipc_temp_files",temporary.length)
                        .put("elapsed_ms",out.elapsedMs).put("cancel_to_exit_ms",out.cancelToExitMs));
                    Runnable task;while((task=notifications.poll())!=null)task.run();
                    assertSame(out,notification.get(1,TimeUnit.SECONDS));
                    active=null;
                }
            } finally {
                if(active!=null && !active.finished) {active.cancel();assertTrue(active.outcomeSignal().get(5,TimeUnit.SECONDS).deathConfirmed);}
                Runnable task;while((task=notifications.poll())!=null)task.run();
            }
        }
        try(FileOutputStream out=context.openFileOutput("completion-signal-evidence.json",0)) {
            out.write(new JSONObject().put("source_commit",BuildConfig.QA_SOURCE_COMMIT).put("rows",rows)
                .put("api",android.os.Build.VERSION.SDK_INT).put("page_size",android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
                .put("published_elapsed_ms",SystemClock.elapsedRealtime()).put("production_ready",false)
                .put("physical_samsung_root_cause_confirmed",false).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
