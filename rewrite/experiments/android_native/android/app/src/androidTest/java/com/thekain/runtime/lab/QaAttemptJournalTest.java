package com.thekain.runtime.lab;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class QaAttemptJournalTest {
    @Test public void failedAttemptAndPendingSnapshotSurviveRestartAndSeparateSuccessfulAttempt() throws Exception {
        JSONObject state=new JSONObject().put("qa_failure_class","TimeoutException");QaAttemptJournal j=new QaAttemptJournal(state);j.restart();
        String first=j.begin("SUITE");JSONObject pending=j.scenario("original-worker","BLOCK_MAIN_DEADLINE");
        pending.put("runtime_snapshot",new JSONObject().put("worker_death_observed",false).put("lease_observation","ARMED"))
            .put("wait_status","WAIT_EXCEEDED").put("wait_stage","COMPLETION");j.failed("TimeoutException");
        j.restart();assertFalse(state.has("pending"));assertEquals("original-worker",state.getJSONArray("unfinished_operations").getJSONObject(0).getString("id"));
        assertFalse(state.getJSONArray("unfinished_operations").getJSONObject(0).getJSONObject("runtime_snapshot").getBoolean("worker_death_observed"));
        String next=j.begin("PROBE");assertNotEquals(first,next);j.scenario("new-worker","PROBE");j.scenarioCompleted();j.completed();
        assertFalse(state.has("qa_failure_class"));JSONArray history=state.getJSONArray("attempts");
        assertEquals("FAILED",history.getJSONObject(0).getString("status"));assertEquals("LEGACY",history.getJSONObject(0).getString("kind"));
        assertEquals("TimeoutException",history.getJSONObject(1).getString("failure_class"));assertEquals("FAILED",history.getJSONObject(1).getString("status"));
        assertEquals("COMPLETED",history.getJSONObject(2).getString("status"));assertEquals(first,state.getString("last_suite_attempt_id"));
    }
    @Test public void unfinishedAttemptNeverBecomesSuccessOrLosesPendingIdentity() throws Exception {
        JSONObject state=new JSONObject();QaAttemptJournal j=new QaAttemptJournal(state);j.restart();j.begin("SUITE");j.scenario("alive-worker","BLOCK_MAIN_DEADLINE");
        try {j.completed();fail("Missing outcome accepted");}catch(IllegalStateException expected) { }
        j.restart();assertEquals("INTERRUPTED",state.getJSONArray("attempts").getJSONObject(0).getString("status"));
        assertEquals("alive-worker",state.getJSONArray("unfinished_operations").getJSONObject(0).getString("id"));
        for(int i=0;i<25;i++) {j.begin("PROBE");j.scenario("worker-"+i,"PROBE");j.failed("TimeoutException");j.restart();}
        assertEquals(QaAttemptJournal.ATTEMPT_LIMIT,state.getJSONArray("attempts").length());
        assertEquals(QaAttemptJournal.UNFINISHED_LIMIT,state.getJSONArray("unfinished_operations").length());
    }
    @Test public void transitionSnapshotsAreBoundedIndependentCopiesAndRejectUnknownPhases() throws Exception {
        OperationTrace trace=new OperationTrace();for(int i=0;i<100;i++)trace.mark("SUBMITTED");
        JSONObject copy=trace.snapshot();copy.getJSONArray("events").getJSONObject(0).put("phase","FORGED");
        assertEquals("SUBMITTED",trace.snapshot().getJSONArray("events").getJSONObject(0).getString("phase"));
        assertEquals(1,trace.snapshot().getJSONArray("events").length());
        try {trace.mark("RESET_LEASE");fail("Unknown phase");}catch(IllegalArgumentException expected) { }
    }
    @Test public void lostCompletionDeliveryTimeoutCapturesRealLiveWorkerWithoutClaimingDeath() throws Exception {
        android.content.Context context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject state=new JSONObject();QaAttemptJournal journal=new QaAttemptJournal(state);journal.restart();journal.begin("DIAGNOSTIC_CYCLE");
        java.io.File home=new java.io.File(context.getFilesDir(),"diagnostic-wait-fixture");
        try(OwnedQaFixture fixture=new OwnedQaFixture(context,home);IsolatedRuntime runtime=new IsolatedRuntime(context,home)) {
            IsolatedRuntime.Operation op=runtime.submit(RuntimeRequest.HANG,null,1,30000,RuntimeRequest.MAX_RSS,IsolatedInput.BLOCK_MAIN);
            try {
                op.whenPythonStarted().get(25,java.util.concurrent.TimeUnit.SECONDS);
                JSONObject pending=journal.scenario(op.id,"INJECTED_LOST_COMPLETION_DELIVERY");
                java.util.concurrent.CompletableFuture<Object> lostDelivery=new java.util.concurrent.CompletableFuture<>();
                try {
                    QaWait.await(lostDelivery,1,(status,future,until)->pending.put("wait_status",status)
                        .put("wait_stage","COMPLETION").put("wait_deadline_elapsed_ms",until)
                        .put("awaited_future_done",future.isDone()).put("runtime_snapshot",runtime.diagnosticSnapshot(op)));
                    fail("Missing result accepted as timeout success");
                }catch(java.util.concurrent.TimeoutException expected) {journal.failed("TimeoutException");}
                assertEquals("WAIT_EXCEEDED",pending.getString("wait_status"));assertFalse(pending.getBoolean("awaited_future_done"));
                JSONObject snapshot=pending.getJSONObject("runtime_snapshot");assertTrue(snapshot.getBoolean("python_entered"));
                assertFalse(snapshot.getBoolean("worker_death_observed"));assertFalse(snapshot.getBoolean("deadline_expired"));
                assertFalse(snapshot.getBoolean("runtime_future_done"));assertFalse(snapshot.getBoolean("death_proof_granted_by_snapshot"));
                assertEquals("ARMED",snapshot.getString("lease_observation"));assertFalse(op.completion().isDone());
                journal.restart();assertEquals(op.id,state.getJSONArray("unfinished_operations").getJSONObject(0).getString("id"));
                assertEquals("FAILED",state.getJSONArray("attempts").getJSONObject(0).getString("status"));
            }finally {
                op.cancel();IsolatedRuntime.Outcome out=op.completion().get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(RuntimeResult.CANCELED,out.result.error);assertTrue(out.deathConfirmed);assertTrue(out.ownerCanaryIntact);
            }
        }
    }

}
