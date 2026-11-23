package com.thekain.runtime.lab;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class RecoveryQaTest {
    @Test public void sdkFreeExplicitSupervisorRecoveryRunsWithoutResettingOldFailure() throws Exception {
        LabApplication app=(LabApplication)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        IsolatedQaSession session=app.isolatedSession();long until=SystemClock.elapsedRealtime()+30000;
        while(!session.idle() && SystemClock.elapsedRealtime()<until)Thread.sleep(50);assertTrue(session.display(),session.idle());
        try(ActivityScenario<IsolatedQaActivity> scenario=ActivityScenario.launch(IsolatedQaActivity.class)) {
            scenario.onActivity(activity->{assertTrue(activity.findViewById(R.id.uid_recovery_test_button).isEnabled());activity.findViewById(R.id.uid_recovery_test_button).performClick();});
            until=SystemClock.elapsedRealtime()+45000;
            while(!session.idle() && SystemClock.elapsedRealtime()<until)Thread.sleep(50);assertTrue(session.display(),session.idle());
            JSONObject state=new JSONObject(ProcessIdentity.read(new File(app.getFilesDir(),"isolated-qa-report.json").getPath(),256*1024));
            assertFalse(state.has("qa_failure_class"));JSONObject trial=state.getJSONArray("recovery_trials").getJSONObject(0);
            assertTrue(trial.getBoolean("kernel_lock_exclusive"));assertEquals(RuntimeResult.CLEANUP_FAILED,trial.getInt("original_error"));
            assertFalse(trial.getBoolean("original_death_confirmed"));JSONObject fresh=trial.getJSONObject("fresh_analysis");
            assertEquals(0,fresh.getInt("error"));assertTrue(fresh.getBoolean("death_confirmed"));assertEquals(0,fresh.getInt("guard_files"));
            android.graphics.Bitmap shot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(shot);
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(app.getFilesDir(),"recovery-qa.png"))) {assertTrue(shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
            finally {shot.recycle();}
        }
    }
}
