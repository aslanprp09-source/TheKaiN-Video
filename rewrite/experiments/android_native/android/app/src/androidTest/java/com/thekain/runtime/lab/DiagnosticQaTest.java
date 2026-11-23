package com.thekain.runtime.lab;

import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Repeat the actual QA implementation in one owner lifecycle, beyond the unchanged full14 UI test. */
@RunWith(AndroidJUnit4.class)
public final class DiagnosticQaTest {
    private JSONObject read(LabApplication app) throws Exception {
        return new JSONObject(app.isolatedSession().reportSnapshot());
    }
    private Set<String> phases(JSONObject row) throws Exception {
        JSONArray events=row.getJSONObject("transition_log").getJSONArray("events");Set<String> phases=new HashSet<>();long previous=-1;
        for(int i=0;i<events.length();i++) {JSONObject e=events.getJSONObject(i);assertTrue(e.getLong("elapsed_ms")>=previous);
            previous=e.getLong("elapsed_ms");assertTrue(e.getLong("wall_ms")>0);assertTrue(phases.add(e.getString("phase")));}
        assertEquals(0,row.getJSONObject("transition_log").getInt("dropped"));return phases;
    }
    @Test public void threeCancellationDeadlineRecoveryAnalysisCyclesShareOneApplicationLifecycle() throws Exception {
        LabApplication app=(LabApplication)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        IsolatedQaSession session=app.isolatedSession();int owner=android.os.Process.myPid();
        long until=SystemClock.elapsedRealtime()+30000;
        while(!session.idle() && SystemClock.elapsedRealtime()<until)Thread.sleep(50);assertTrue(session.display(),session.idle());
        JSONArray results=new JSONArray();Set<String> attempts=new HashSet<>(),operations=new HashSet<>();
        try(ActivityScenario<IsolatedQaActivity> scenario=ActivityScenario.launch(IsolatedQaActivity.class)) {
            for(int cycle=0;cycle<3;cycle++) {
                JSONObject before=read(app);int count=before.optJSONArray("cases")==null?0:before.getJSONArray("cases").length();
                int recoveryCount=before.optJSONArray("recovery_trials")==null?0:before.getJSONArray("recovery_trials").length();
                scenario.onActivity(activity->session.start(4));until=SystemClock.elapsedRealtime()+80000;
                boolean sawLiveDeadline=false,exportChecked=false;
                while(!session.idle() && SystemClock.elapsedRealtime()<until) {
                    assertFalse(session.display(),session.display().startsWith("Проверка остановлена:"));
                    JSONObject pending=read(app).optJSONObject("pending");
                    if(pending!=null && pending.optString("scenario_name").equals("BLOCK_MAIN_DEADLINE") && pending.has("runtime_snapshot")) {
                        JSONObject snapshot=pending.getJSONObject("runtime_snapshot");
                        if(snapshot.getBoolean("python_entered")) {
                            sawLiveDeadline=true;assertTrue(snapshot.getBoolean("supervisor_alive"));
                            assertFalse(snapshot.getBoolean("worker_death_observed"));assertFalse(snapshot.getBoolean("death_proof_granted_by_snapshot"));
                            assertTrue(snapshot.has("service_record_observation"));assertTrue(snapshot.getLong("observer_last_sequence")>0);
                            assertEquals("ARMED",snapshot.getString("lease_observation"));
                            scenario.onActivity(activity->{assertFalse(activity.findViewById(R.id.uid_kill_owner_button).isEnabled());assertTrue(activity.findViewById(R.id.uid_export_button).isEnabled());});
                            if(!exportChecked) {
                                java.util.concurrent.CountDownLatch saved=new java.util.concurrent.CountDownLatch(1);
                                java.util.concurrent.atomic.AtomicBoolean ok=new java.util.concurrent.atomic.AtomicBoolean();
                                File export=new File(app.getFilesDir(),"diagnostic-live-export.json");
                                session.export(android.net.Uri.fromFile(export),value->{ok.set(value);saved.countDown();});
                                assertTrue("Export queued behind active QA wait",saved.await(5,java.util.concurrent.TimeUnit.SECONDS));assertTrue(ok.get());
                                JSONObject live=new JSONObject(ProcessIdentity.read(export.getPath(),256*1024));
                                assertTrue(live.getBoolean("exported_while_busy"));assertTrue(live.has("pending"));
                                assertTrue(live.getJSONObject("export_runtime_observation").getBoolean("diagnostic_only"));
                                exportChecked=true;
                            }
                        }
                    }
                    Thread.sleep(80);
                }
                assertTrue(session.display(),session.idle());assertTrue("No real in-flight deadline snapshot",sawLiveDeadline);assertTrue("No live export",exportChecked);
                assertSame(session,app.isolatedSession());assertEquals(owner,android.os.Process.myPid());
                JSONObject state=read(app);assertFalse(state.has("qa_failure_class"));assertFalse(state.has("pending"));
                String attempt=state.getString("current_attempt_id");assertTrue(attempts.add(attempt));
                JSONArray rows=state.getJSONArray("cases");assertEquals(count+2,rows.length());
                JSONObject cancel=rows.getJSONObject(count),deadline=rows.getJSONObject(count+1);
                assertEquals(1,cancel.getInt("error"));assertEquals(2,deadline.getInt("error"));
                assertEquals("BLOCK_MAIN_CANCEL",cancel.getString("scenario_name"));assertEquals("BLOCK_MAIN_DEADLINE",deadline.getString("scenario_name"));
                assertFalse(phases(cancel).contains("DEADLINE"));
                assertTrue(phases(deadline).containsAll(Arrays.asList("SUBMITTED","STARTED","DEADLINE","RETIRING","CLEANUP","COMPLETED")));
                for(JSONObject row:Arrays.asList(cancel,deadline)) {
                    assertEquals(attempt,row.getString("attempt_id"));assertTrue(operations.add(row.getString("id")));
                    assertTrue(row.getBoolean("death_confirmed"));assertTrue(row.getBoolean("owner_canary_intact"));
                    assertEquals(0,row.getInt("owned_ipc_temp_files"));assertFalse(row.getBoolean("main_python_loaded"));
                }
                assertEquals(recoveryCount+1,state.getJSONArray("recovery_trials").length());
                JSONObject recovery=state.getJSONArray("recovery_trials").getJSONObject(recoveryCount);
                assertEquals(13,recovery.getInt("original_error"));assertFalse(recovery.getBoolean("original_death_confirmed"));
                assertTrue(recovery.getBoolean("kernel_lock_exclusive"));assertTrue(recovery.getBoolean("old_operation_not_rewritten"));
                JSONObject fresh=recovery.getJSONObject("fresh_analysis");assertEquals(0,fresh.getInt("error"));
                assertTrue(fresh.getBoolean("death_confirmed"));assertTrue(fresh.getBoolean("owner_canary_intact"));assertEquals(0,fresh.getInt("guard_files"));
                results.put(new JSONObject().put("attempt_id",attempt).put("owner_pid",owner)
                    .put("cancel",cancel).put("deadline",deadline).put("recovery",recovery).put("live_snapshot_observed",sawLiveDeadline));
            }
        }
        try(FileOutputStream out=app.openFileOutput("diagnostic-cycle-evidence.json",0)) {
            out.write(new JSONObject().put("source_commit",BuildConfig.QA_SOURCE_COMMIT).put("cycles",results)
                .put("same_application_lifecycle",true).put("production_ready",false).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
