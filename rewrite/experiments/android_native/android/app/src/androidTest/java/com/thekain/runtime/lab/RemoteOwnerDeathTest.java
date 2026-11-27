package com.thekain.runtime.lab;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Driver treats the intentional preparation crash as a crash, never a passed/missing timeout test. */
@RunWith(AndroidJUnit4.class)
public final class RemoteOwnerDeathTest {
    @Test public void prepareAndKillOwner() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String attempt=InstrumentationRegistry.getArguments().getString("attempt");assertNotNull(attempt);assertTrue(attempt.matches("[0-9a-f-]{36}"));
        RemoteHttpsTransport.Policy policy=RemoteHttpsTransport.Policy.publicOrigins(Collections.singleton("https://owned.example.org")).fault(1,RuntimeRequest.MAX_RSS,10000);
        RuntimeRequest request=new RuntimeRequest(UUID.randomUUID().toString(),RuntimeRequest.SOURCE_ANALYZE,"https://owned.example.org/dns",1,SystemClock.elapsedRealtime()+30000,RuntimeRequest.MAX_RSS,RuntimeRequest.MAX_HEADROOM);
        RemoteHttpsTransport transport=(RemoteHttpsTransport)policy.create(context,request,(count,bytes)->{});
        Executors.newSingleThreadExecutor().submit(()->transport.reply(request.url,"GET"));assertTrue(transport.lookupSignal().get(10,TimeUnit.SECONDS));
        JSONObject evidence=transport.evidence().put("owner_pid",android.os.Process.myPid()).put("owner_start",ProcessIdentity.selfStartTicks())
            .put("actual_dns_entered",true).put("attempt",attempt).put("source_commit",BuildConfig.QA_SOURCE_COMMIT);
        JSONObject pending=new JSONObject(ProcessIdentity.read(new File(context.getFilesDir(),"https-pending.json").getPath(),4096));
        assertEquals("ARMED",pending.getString("phase"));assertFalse(evidence.getBoolean("kernel_process_gone"));
        try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),"network-owner-death-marker.json"))) {out.write(evidence.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
        android.os.Process.killProcess(android.os.Process.myPid());throw new AssertionError("Owner self-kill returned");
    }
    @Test public void recoverOnlyAfterIndependentDriverConfirmsActualBrokerExit() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject old=new JSONObject(ProcessIdentity.read(new File(context.getFilesDir(),"network-owner-death-marker.json").getPath(),16384));
        assertNotEquals(old.getInt("owner_pid"),android.os.Process.myPid());assertTrue(old.getBoolean("actual_dns_entered"));
        assertTrue(BrokerProcess.absent(old.getInt("pid"),old.getLong("start_ticks")));
        assertEquals(IsolatedLease.RECOVERED,RemoteHttpsTransport.recover(context));
        String url="https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/11fa103624275958f089d9566c7722fc64133094/rewrite/android/app/src/androidTest/assets/owned-sample.mp4";
        RuntimeRequest request=new RuntimeRequest(UUID.randomUUID().toString(),RuntimeRequest.SOURCE_ANALYZE,url,1,SystemClock.elapsedRealtime()+30000,RuntimeRequest.MAX_RSS,RuntimeRequest.MAX_HEADROOM);
        RemoteHttpsTransport transport=(RemoteHttpsTransport)RemoteHttpsTransport.Policy.publicOrigins(Collections.singleton("https://raw.githubusercontent.com")).create(context,request,(count,bytes)->{});
        try {assertEquals(2428,transport.reply(url,"GET").body.length);}finally {transport.close();}
        long end=SystemClock.elapsedRealtime()+5000;while(!transport.terminated() && SystemClock.elapsedRealtime()<end)Thread.sleep(10);assertTrue(transport.terminated());
        JSONObject proof=transport.evidence();assertNotEquals(old.getString("instance"),proof.getString("instance"));
        proof.put("recovered",true).put("old_owner_pid",old.getInt("owner_pid")).put("old_network_pid",old.getInt("pid"))
            .put("old_result_not_fabricated",true).put("source_commit",BuildConfig.QA_SOURCE_COMMIT);
        try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),"network-owner-recovery.json"))) {out.write(proof.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
}
