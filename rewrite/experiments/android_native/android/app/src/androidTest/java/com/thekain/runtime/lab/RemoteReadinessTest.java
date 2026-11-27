package com.thekain.runtime.lab;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import okhttp3.mockwebserver.*;
import okhttp3.tls.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Actual Binder configure callback/return ordering, real TLS, real worker death. No relaxed old assertion. */
@RunWith(AndroidJUnit4.class)
public final class RemoteReadinessTest {
    private Context context;private MockWebServer server;private String origin;private RemoteHttpsTransport.Policy fixture;
    private final List<RemoteHttpsTransport> owned=new ArrayList<>();
    private final JSONArray proofs=new JSONArray();
    @Before public void setup() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        HeldCertificate certificate=new HeldCertificate.Builder().commonName("owned fixture").addSubjectAlternativeName("127.0.0.1").build();
        server=new MockWebServer();server.useHttps(new HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(),false);
        server.setDispatcher(new Dispatcher() {public MockResponse dispatch(RecordedRequest request) {
            return new MockResponse().setHeader("Content-Type","text/html").setBody("owned");
        }});
        server.start(InetAddress.getByName("127.0.0.1"),0);origin="https://127.0.0.1:"+server.getPort();
        fixture=RemoteHttpsTransport.Policy.fixture(origin,certificate.certificate().getEncoded());
        assertEquals(IsolatedLease.NO_PENDING,RemoteHttpsTransport.recover(context));
    }
    private RemoteHttpsTransport create(RemoteHttpsTransport.Policy policy) throws Exception {
        RuntimeRequest request=new RuntimeRequest(UUID.randomUUID().toString(),RuntimeRequest.SOURCE_ANALYZE,origin+"/owned",1,
            SystemClock.elapsedRealtime()+10000,RuntimeRequest.MAX_RSS,RuntimeRequest.MAX_HEADROOM);
        RemoteHttpsTransport value=(RemoteHttpsTransport)policy.create(context,request,(count,bytes)->{});owned.add(value);return value;
    }
    private void retired(RemoteHttpsTransport value,int failure) throws Exception {
        value.close();long end=SystemClock.elapsedRealtime()+5000;
        while(!value.terminated() && SystemClock.elapsedRealtime()<end)Thread.sleep(10);
        assertTrue(value.terminated());JSONObject proof=value.evidence();
        assertEquals(failure,proof.getInt("failure"));assertTrue(proof.getBoolean("kernel_process_gone"));
        assertTrue(proof.getBoolean("binder_dead"));assertTrue(proof.getBoolean("service_absent"));assertTrue(proof.getBoolean("lease_clean"));
        assertTrue(proof.getBoolean("ready_notification_seen"));assertTrue(proof.getBoolean("configure_returned"));assertTrue(proof.getBoolean("ready_published"));
        assertEquals("NONE",proof.getString("configure_failure"));assertTrue(proof.getLong("retire_ms")<=2500);
        assertTrue(BrokerProcess.absent(proof.getInt("pid"),proof.getLong("start_ticks")));proofs.put(proof);
        assertFalse(new File(context.getFilesDir(),"https-pending.json").exists());
        String[] guards=context.getFilesDir().list((dir,name)->name.startsWith("https-lease-"));assertNotNull(guards);assertEquals(0,guards.length);
    }
    private void fresh() throws Exception {
        RemoteHttpsTransport next=create(fixture);
        assertEquals("owned",new String(next.reply(origin+"/owned","GET").body,StandardCharsets.UTF_8));retired(next,0);
    }
    private void record(String name,JSONObject row) throws Exception {
        row.put("case",name).put("source_commit",BuildConfig.QA_SOURCE_COMMIT).put("api",android.os.Build.VERSION.SDK_INT)
            .put("pages",android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)).put("proofs",proofs).put("production_ready",false);
        File folder=new File(context.getFilesDir(),"readiness-evidence");assertTrue(folder.mkdirs() || folder.isDirectory());
        try(FileOutputStream output=new FileOutputStream(new File(folder,name+".json"))) {output.write(row.toString().getBytes(StandardCharsets.UTF_8));}
    }
    @Test public void readyNotificationCannotPublishAdmissionBeforeConfigureReturns() throws Exception {
        ExecutorService caller=Executors.newSingleThreadExecutor();JSONArray held=new JSONArray();
        try {
            for(int attempt=0;attempt<3;attempt++) {
                CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
                RemoteHttpsTransport.Policy policy=fixture.holdReadyReturnForTests(()->{
                    entered.countDown();try {if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("TEST_BARRIER_NOT_RELEASED");}
                    catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException("TEST_BARRIER_INTERRUPTED");}
                });
                RemoteHttpsTransport value=create(policy);
                try {
                    assertTrue(entered.await(5,TimeUnit.SECONDS));int requests=server.getRequestCount();
                    Future<IsolatedReply> result=caller.submit(()->value.reply(origin+"/owned","GET"));
                    // A bounded observation while a deliberately held callback is pending, not a successful operation timeout.
                    try {result.get(200,TimeUnit.MILLISECONDS);fail("Operation passed an unreturned configure transaction");}
                    catch(TimeoutException expected) { }
                    JSONObject pending=value.evidence();assertTrue(pending.getBoolean("ready_notification_seen"));
                    assertFalse(pending.getBoolean("configure_returned"));assertFalse(pending.getBoolean("ready_published"));
                    assertEquals(0,pending.getInt("requests"));assertFalse(result.isDone());assertEquals(requests,server.getRequestCount());
                    held.put(pending);
                    release.countDown();assertEquals("owned",new String(result.get(7,TimeUnit.SECONDS).body,StandardCharsets.UTF_8));
                    assertEquals(requests+1,server.getRequestCount());retired(value,0);fresh();
                }finally {release.countDown();}
            }
        }finally {caller.shutdownNow();assertTrue(caller.awaitTermination(5,TimeUnit.SECONDS));}
        record("barrier",new JSONObject().put("actual_binder_callback_held",true).put("actual_tls_after_return",true)
            .put("no_early_network",true).put("fresh_after_each",true).put("held",held));
    }
    @Test public void repeatedActualCrashAfterReadyKeepsWorkerDiedAndFreshTls() throws Exception {
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try {
            for(int attempt=0;attempt<3;attempt++) {
                RemoteHttpsTransport value=create(fixture.fault(2,RuntimeRequest.MAX_RSS,10000));
                Future<?> result=caller.submit(()->value.reply(origin+"/owned","GET"));
                try {result.get(7,TimeUnit.SECONDS);fail("Actual crash returned success");}
                catch(ExecutionException failed) {
                    assertTrue(failed.getCause() instanceof ChildNetworkGate.Failure);
                    assertEquals(RuntimeResult.WORKER_DIED,((ChildNetworkGate.Failure)failed.getCause()).code);
                }
                retired(value,RuntimeResult.WORKER_DIED);fresh();
            }
        }finally {caller.shutdownNow();assertTrue(caller.awaitTermination(5,TimeUnit.SECONDS));}
        record("crash",new JSONObject().put("actual_crashes",3).put("exact_worker_died",true).put("fresh_after_each",true));
    }
    @After public void cleanup() throws Exception {
        try {for(RemoteHttpsTransport value:owned) {
            value.close();long end=SystemClock.elapsedRealtime()+5000;
            while(!value.terminated() && SystemClock.elapsedRealtime()<end)Thread.sleep(10);assertTrue(value.terminated());
        }}finally {server.shutdown();}
    }
}
