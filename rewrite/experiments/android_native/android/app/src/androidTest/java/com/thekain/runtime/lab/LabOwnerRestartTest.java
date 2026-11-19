package com.thekain.runtime.lab;

import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Verification follows guarded owner self-kill from a normal, non-instrumented Activity. */
@RunWith(AndroidJUnit4.class)
public class LabOwnerRestartTest {
    private LabApplication app() {return (LabApplication)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();}
    @Test public void verify() throws Exception {
        LabApplication app=app();JSONObject marker=new JSONObject(ProcessIdentity.read(new File(app.getFilesDir(),"qa-evidence/owner-marker.json").getPath(),4096));
        assertNotEquals(marker.getInt("parent_pid"),android.os.Process.myPid());
        LabSession session=app.session();session.whenReady().get(30,TimeUnit.SECONDS);
        JSONObject report=new JSONObject(session.report());assertFalse(report.has("pending"));
        assertEquals("Driver must prove orphan death before recovery can mask it",0,report.getJSONObject("startup_recovery").getInt("kill_attempts"));
        int matches=0;JSONArray cases=report.getJSONArray("cases");
        for(int i=0;i<cases.length();i++) {JSONObject item=cases.getJSONObject(i);if(!item.getString("id").equals(marker.getString("id")))continue;
            matches++;assertEquals("INTERRUPTED_OWNER_OR_SESSION",item.getString("outcome"));assertTrue(item.isNull("error"));
            assertTrue(item.getBoolean("parent_identity_changed"));assertTrue(item.getBoolean("death_confirmed_on_relaunch"));}
        assertEquals("Interrupted operation must have one terminal journal record",1,matches);
        NativeRuntime.Outcome fresh=session.start(RuntimeRequest.ANALYZE).get(35,TimeUnit.SECONDS);
        assertEquals(RuntimeResult.OK,fresh.result.error);assertTrue(fresh.deathConfirmed);assertNotEquals(marker.getString("id"),fresh.result.id);
        try(FileOutputStream out=new FileOutputStream(new File(app.getFilesDir(),"qa-evidence/owner-restart-report.json"))) {
            out.write(session.report().getBytes(StandardCharsets.UTF_8));out.getFD().sync();
        }
        app.resetForTest();
    }
}
