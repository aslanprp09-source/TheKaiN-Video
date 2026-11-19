package com.thekain.runtime.lab;

import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Additional lifecycle checks; all original 23 native/process tests remain separate. */
@RunWith(AndroidJUnit4.class)
public class LabLifecycleTest {
    private LabApplication app;
    private LabSession session;
    @Before public void prepare() throws Exception {
        app=(LabApplication)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        app.resetForTest();new android.util.AtomicFile(new File(app.getFilesDir(),"runtime-qa-state.json")).delete();
        session=app.session();session.whenReady().get(30,TimeUnit.SECONDS);
        shell("input keyevent 224");shell("wm dismiss-keyguard");
    }
    @After public void finish() throws Exception {
        shell("input keyevent 224");shell("wm dismiss-keyguard");app.resetForTest();
    }
    private static void shell(String fixed) throws IOException {
        try(ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(fixed);
            InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)) {while(in.read()!=-1) { }}
    }
    private void focused(ActivityScenario<LabQaActivity> scenario) throws Exception {
        AtomicBoolean focused=new AtomicBoolean();long until=SystemClock.elapsedRealtime()+8000;
        do {
            scenario.onActivity(activity->focused.set(activity.hasWindowFocus() && activity.getWindow().getDecorView().isShown()));
            if(focused.get())return;Thread.sleep(50);
        } while(SystemClock.elapsedRealtime()<until);
        throw new AssertionError("QA Activity window never became ready");
    }
    private void screenshot(String name) throws Exception {
        File folder=new File(app.getFilesDir(),"qa-evidence");assertTrue(folder.mkdirs()||folder.isDirectory());
        Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);
        try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))) {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}
        finally {image.recycle();}
    }
    private LabSession.Active started() throws Exception {
        long until=SystemClock.elapsedRealtime()+2000;LabSession.Active active;
        while((active=session.active())==null && SystemClock.elapsedRealtime()<until)Thread.sleep(10);
        assertNotNull(active);active.started.get(20,TimeUnit.SECONDS);return active;
    }
    private NativeRuntime.Outcome success() throws Exception {
        NativeRuntime.Outcome out=session.start(RuntimeRequest.ANALYZE).get(35,TimeUnit.SECONDS);
        assertEquals(RuntimeResult.OK,out.result.error);assertEquals(1,out.result.pythonCalls);
        assertEquals(1,out.result.formats);assertFalse(out.result.downloadEnabled);assertTrue(out.deathConfirmed);
        assertFalse(ProcessIdentity.read("/proc/self/maps",2*1024*1024).contains("libpython3.14.so"));return out;
    }
    @Test public void recreationKeepsSingleOwnerAndExternalExtrasCannotStartPython() throws Exception {
        // Simulate the API26 crash window after base -> .bak, before the next base exists.
        File journal=new File(app.getCacheDir(),"qa-store-recovery-test.json");
        android.util.AtomicFile atomic=new android.util.AtomicFile(journal);atomic.delete();
        try {
            JSONObject saved=new JSONObject().put("schema",1).put("cases",new JSONArray()).put("pending",new JSONObject().put("id","test-marker"));
            try(FileOutputStream out=new FileOutputStream(new File(journal.getPath()+".bak"))) {
                out.write(saved.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();
            }
            assertFalse(journal.exists());
            assertEquals("test-marker",new LabQaStore(journal).read().getJSONObject("pending").getString("id"));
        } finally {atomic.delete();}
        Intent external=new Intent(app,LabQaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("python","raise SystemExit(1)").putExtra("operation",RuntimeRequest.HANG)
            .putExtra("url","https://example.invalid/private");
        try(ActivityScenario<LabQaActivity> scenario=ActivityScenario.launch(external)) {
            focused(scenario);assertNull(session.active());assertEquals(0,new JSONObject(session.report()).getJSONArray("cases").length());
            session.operatorSettings(true,2); // Queue is drained before start returns its result.
            screenshot("qa-ready");CompletableFuture<NativeRuntime.Outcome> result=session.start(RuntimeRequest.HANG);
            LabSession.Active active=started();String id=active.operation.id;screenshot("qa-running");
            scenario.recreate();focused(scenario);assertSame(session,app.session());assertEquals(id,session.active().operation.id);
            scenario.onActivity(activity->activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            long until=SystemClock.elapsedRealtime()+8000;AtomicBoolean landscape=new AtomicBoolean();
            do {
                scenario.onActivity(activity->landscape.set(activity.hasWindowFocus() && activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE));
                if(landscape.get())break;Thread.sleep(50);
            } while(SystemClock.elapsedRealtime()<until);
            assertTrue("Landscape window was not ready",landscape.get());screenshot("qa-landscape-running");
            scenario.onActivity(activity->{
                android.view.View cancel=activity.findViewById(R.id.lab_cancel);android.graphics.Rect rect=new android.graphics.Rect();
                assertTrue(cancel.isEnabled());assertTrue("Cancel must remain fully visible in landscape",cancel.getGlobalVisibleRect(rect));
                assertEquals(cancel.getHeight(),rect.height());assertTrue(cancel.performClick());
            });
            NativeRuntime.Outcome out=result.get(5,TimeUnit.SECONDS);
            assertEquals(RuntimeResult.CANCELED,out.result.error);assertTrue(out.deathConfirmed);
            assertTrue(new JSONObject(session.report()).getBoolean("operator_claimed_physical_device"));
            assertEquals("restricted",new JSONObject(session.report()).getString("operator_battery_setting"));
            assertNotEquals(out.instance,success().instance);
        }
    }
    @Test public void homeAndScreenOffKeepDeadlineBoundedAndNextWorkerFresh() throws Exception {
        try(ActivityScenario<LabQaActivity> scenario=ActivityScenario.launch(LabQaActivity.class)) {
            focused(scenario);CompletableFuture<NativeRuntime.Outcome> result=session.deadline();
            LabSession.Active active=started();shell("input keyevent 3");shell("input keyevent 223");
            PowerManager power=(PowerManager)app.getSystemService(Context.POWER_SERVICE);
            long until=SystemClock.elapsedRealtime()+2000;while(power.isInteractive()&&SystemClock.elapsedRealtime()<until)Thread.sleep(20);
            assertFalse("Screen-off scenario was not reached",power.isInteractive());
            NativeRuntime.Outcome out=result.get(10,TimeUnit.SECONDS);
            assertEquals(RuntimeResult.TIMEOUT,out.result.error);assertTrue(out.elapsedMs>=6000&&out.elapsedMs<9000);
            assertTrue(out.deathConfirmed);assertTrue(active.child.exited());
            shell("input keyevent 224");shell("wm dismiss-keyguard");
            assertNotEquals(out.instance,success().instance);
            JSONArray events=new JSONObject(session.report()).getJSONArray("events");boolean stopped=false,screenOff=false;
            for(int i=0;i<events.length();i++) {JSONObject e=events.getJSONObject(i);
                stopped|=e.getString("kind").equals("activity_stop");screenOff|=!e.getJSONObject("power").getBoolean("interactive");}
            assertTrue("Missing Activity background observation",stopped);assertTrue("Missing screen-off observation",screenOff);
        }
    }
    @Test public void killingVerifiedServiceDoesNotKillOwnerAndRestartsCleanly() throws Exception {
        CompletableFuture<NativeRuntime.Outcome> result=session.start(RuntimeRequest.HANG);LabSession.Active active=started();
        int parent=android.os.Process.myPid();session.killWorker();NativeRuntime.Outcome out=result.get(5,TimeUnit.SECONDS);
        assertEquals(RuntimeResult.WORKER_DIED,out.result.error);assertTrue(out.deathConfirmed);assertTrue(active.child.exited());
        assertEquals(parent,android.os.Process.myPid());assertNotEquals(out.instance,success().instance);
    }
    @Test public void repeatedAnalysesLeaveNoWorkersOrGrowingTemporaryFiles() throws Exception {
        NativeRuntime.Outcome previous=success();JSONObject before=session.footprint();
        for(int i=0;i<5;i++) {NativeRuntime.Outcome next=success();assertNotEquals(previous.instance,next.instance);previous=next;}
        JSONObject after=session.footprint();assertEquals(0,after.getInt("worker_processes"));assertEquals(0,after.getInt("owned_temp_files"));
        assertEquals(before.getLong("private_files"),after.getLong("private_files"));
        assertTrue("Only bounded journal growth is expected",after.getLong("private_bytes")-before.getLong("private_bytes")<65536);
        app.resetForTest();
        File journal=new File(app.getFilesDir(),"runtime-qa-state.json");
        byte[] invalid="corrupt journal PRIVATE KEY sentinel".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(FileOutputStream out=new FileOutputStream(journal)) {out.write(invalid);out.getFD().sync();}
        session=app.session();
        try {session.whenReady().get(5,TimeUnit.SECONDS);fail("Corrupt journal must fail closed");}
        catch(ExecutionException expected) { }
        assertFalse(session.ready());assertTrue(session.canExport());assertNull(session.active());
        assertTrue(new JSONObject(session.report()).has("qa_error_class"));assertFalse(session.report().contains("PRIVATE KEY"));
        session.event("activity_resume",true);
        try {session.start(RuntimeRequest.PROBE).get(5,TimeUnit.SECONDS);fail("Failed session must not start Python");}
        catch(ExecutionException expected) { }
        assertArrayEquals(invalid,java.nio.file.Files.readAllBytes(journal.toPath()));
        assertFalse(session.report().contains("PRIVATE KEY"));
        new android.util.AtomicFile(journal).delete(); // Test-only cleanup before the independent restart phase.
    }
}
