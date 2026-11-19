package com.thekain.runtime.lab;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Real SDK-free controls and persisted report, in addition to backend security tests. */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class IsolatedQaTest {
    private LabApplication app;
    private IsolatedQaSession session;
    @Before public void setup() throws Exception {
        app=(LabApplication)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        session=app.isolatedSession();long until=SystemClock.elapsedRealtime()+30000;
        while(!session.idle() && SystemClock.elapsedRealtime()<until)Thread.sleep(50);assertTrue(session.display(),session.idle());
    }
    private void click(ActivityScenario<IsolatedQaActivity> scenario,int id) throws Exception {
        AtomicBoolean ready=new AtomicBoolean();long until=SystemClock.elapsedRealtime()+8000;
        do {
            scenario.onActivity(activity->{
                android.view.View button=activity.findViewById(id);
                if(activity.hasWindowFocus() && activity.getWindow().getDecorView().isShown() && button.isShown() && button.isEnabled()) {
                    ready.set(true);button.performClick();
                }
            });
            if(ready.get())return;Thread.sleep(50);
        }while(SystemClock.elapsedRealtime()<until);
        throw new AssertionError("UID QA window/control not ready");
    }
    private JSONObject report() throws Exception {return new JSONObject(ProcessIdentity.read(new File(app.getFilesDir(),"isolated-qa-report.json").getPath(),256*1024));}
    private void idle() throws Exception {
        long until=SystemClock.elapsedRealtime()+180000;
        while(!session.idle() && SystemClock.elapsedRealtime()<until) {
            assertFalse(session.display(),session.display().startsWith("Проверка остановлена:"));Thread.sleep(100);
        }
        assertTrue(session.display(),session.idle());
    }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        File folder=new File(app.getFilesDir(),"isolated-qa-evidence");assertTrue(folder.mkdirs()||folder.isDirectory());
        try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))) {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
        finally {bitmap.recycle();}
    }
    @Test public void aSdkFreeSuiteRunsThroughActualControlsAndExportsBoundedFacts() throws Exception {
        try(ActivityScenario<IsolatedQaActivity> scenario=ActivityScenario.launch(IsolatedQaActivity.class)) {
            screenshot("entry");click(scenario,R.id.uid_suite_button);idle();
            JSONObject j=report();assertEquals(BuildConfig.QA_SOURCE_COMMIT,j.getString("source_commit"));
            assertFalse(j.getBoolean("production_ready"));assertFalse(j.getBoolean("physical_device_attested"));
            JSONArray cases=j.getJSONArray("cases");assertEquals(13,cases.length());
            for(int i=0;i<cases.length();i++) {
                JSONObject row=cases.getJSONObject(i);assertEquals(row.toString(),row.getInt("expected_error"),row.getInt("error"));
                assertTrue(row.getBoolean("death_confirmed"));assertFalse(row.getBoolean("main_python_loaded"));
                if(row.getInt("worker_pid")>0)assertNotEquals(row.getInt("parent_uid"),row.getInt("worker_uid"));
            }
            JSONObject probe=cases.getJSONObject(0);assertEquals(64,probe.getInt("pointer_bits"));assertTrue(probe.getBoolean("openssl_handshake"));
            assertTrue(probe.getString("machine").matches("aarch64|x86_64"));assertFalse(j.has("pending"));
            screenshot("suite-result");
        }
    }
    @Test public void bExternalExtrasCannotStartWorkAndRecreationKeepsCancelableWorker() throws Exception {
        int before=report().getJSONArray("cases").length();
        Intent external=new Intent(app,IsolatedQaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("python","raise SystemExit(1)").putExtra("url","file:///etc/passwd").putExtra("operation",4);
        try(ActivityScenario<IsolatedQaActivity> scenario=ActivityScenario.launch(external)) {
            Thread.sleep(200);assertEquals(before,report().getJSONArray("cases").length());assertTrue(session.idle());
            click(scenario,R.id.uid_hang_button);
            long until=SystemClock.elapsedRealtime()+25000;
            while(!session.display().contains("PID worker:") && SystemClock.elapsedRealtime()<until)Thread.sleep(50);
            assertTrue(session.display(),session.display().contains("PID worker:"));
            scenario.recreate();click(scenario,R.id.uid_cancel_button);idle();
            JSONArray cases=report().getJSONArray("cases");assertEquals(before+1,cases.length());
            JSONObject last=cases.getJSONObject(cases.length()-1);assertEquals(1,last.getInt("error"));assertTrue(last.getBoolean("death_confirmed"));
            screenshot("recreated-cancelled");
        }
    }
}
