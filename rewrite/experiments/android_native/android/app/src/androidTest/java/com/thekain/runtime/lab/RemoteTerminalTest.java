package com.thekain.runtime.lab;

import android.content.Context;
import android.os.SystemClock;
import android.system.*;
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
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real TLS/Binder retirement races, plus explicitly simulated fail-closed kernel transitions. */
@RunWith(AndroidJUnit4.class)
public final class RemoteTerminalTest {
    private Context context;private MockWebServer server;private String origin;private RemoteHttpsTransport.Policy fixture;
    private final List<RemoteHttpsTransport> owned=new ArrayList<>();
    @Before public void setup() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        HeldCertificate cert=new HeldCertificate.Builder().commonName("owned terminal fixture").addSubjectAlternativeName("127.0.0.1").build();
        server=new MockWebServer();server.useHttps(new HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false);
        server.setDispatcher(new Dispatcher() {public MockResponse dispatch(RecordedRequest request) {return new MockResponse().setBody("owned");}});
        server.start(InetAddress.getByName("127.0.0.1"),0);origin="https://127.0.0.1:"+server.getPort();
        fixture=RemoteHttpsTransport.Policy.fixture(origin,cert.certificate().getEncoded());
        assertEquals(IsolatedLease.NO_PENDING,RemoteHttpsTransport.recover(context));
    }
    private RemoteHttpsTransport create(RemoteHttpsTransport.Policy policy,IsolatedTransport.Counters counters) throws Exception {
        RuntimeRequest request=new RuntimeRequest(UUID.randomUUID().toString(),RuntimeRequest.SOURCE_ANALYZE,origin+"/owned",1,
            SystemClock.elapsedRealtime()+10000,RuntimeRequest.MAX_RSS,RuntimeRequest.MAX_HEADROOM);
        RemoteHttpsTransport value=(RemoteHttpsTransport)policy.create(context,request,counters);owned.add(value);return value;
    }
    private JSONObject retired(RemoteHttpsTransport value,int failure) throws Exception {
        value.close();long end=SystemClock.elapsedRealtime()+5000;
        while(!value.terminated() && SystemClock.elapsedRealtime()<end)Thread.sleep(10);
        assertTrue(value.terminated());JSONObject p=value.evidence();assertEquals(failure,p.getInt("failure"));
        for(String key:new String[]{"kernel_process_gone","binder_dead","service_absent","lease_clean","ready_published","configure_returned"})assertTrue(key,p.getBoolean(key));
        assertTrue(p.getLong("retire_ms")<=2500);assertTrue(BrokerProcess.absent(p.getInt("pid"),p.getLong("start_ticks")));
        assertFalse(new File(context.getFilesDir(),"https-pending.json").exists());
        String[] guards=context.getFilesDir().list((dir,name)->name.startsWith("https-lease-"));assertNotNull(guards);assertEquals(0,guards.length);
        return p;
    }
    private JSONObject fresh() throws Exception {
        RemoteHttpsTransport next=create(fixture,(count,bytes)->{});
        assertEquals("owned",new String(next.reply(origin+"/owned","GET").body,StandardCharsets.UTF_8));return retired(next,0);
    }
    private void record(String name,JSONObject row) throws Exception {
        row.put("case",name).put("source_commit",BuildConfig.QA_SOURCE_COMMIT).put("api",android.os.Build.VERSION.SDK_INT)
            .put("pages",Os.sysconf(OsConstants._SC_PAGESIZE)).put("production_ready",false);
        File dir=new File(context.getFilesDir(),"terminal-evidence");assertTrue(dir.mkdirs() || dir.isDirectory());
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".json"))) {out.write(row.toString().getBytes(StandardCharsets.UTF_8));}
    }
    @Test public void provisionalRpcErrorCannotReplaceLatchedDeathOrCancelAndRealIpcStaysIpc() throws Exception {
        ExecutorService caller=Executors.newSingleThreadExecutor();JSONArray rows=new JSONArray();
        try {
            for(String mode:new String[]{"death","cancel","ipc"})for(int attempt=0;attempt<3;attempt++) {
                CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
                RemoteHttpsTransport.Policy policy=fixture.holdFailureReturnForTests(()->{
                    entered.countDown();try {if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("TEST_BARRIER_TIMEOUT");}
                    catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException("TEST_BARRIER_INTERRUPTED");}
                });
                // A trusted owner metrics callback throws after a real, validated TLS reply.
                // No fault mode, executable input or new AIDL capability is given to the Service.
                RemoteHttpsTransport value=create(policy,(count,bytes)->{throw new IllegalStateException("TEST_OWNER_CALLBACK");});
                Future<?> result=caller.submit(()->value.reply(origin+"/owned","GET"));
                int code=mode.equals("death")?RuntimeResult.WORKER_DIED:mode.equals("cancel")?RuntimeResult.CANCELED:RuntimeResult.IPC_ERROR;
                JSONObject before;
                try {
                    assertTrue(entered.await(5,TimeUnit.SECONDS));before=value.evidence();assertTrue(before.getBoolean("ready_published"));
                    assertEquals(1,before.getInt("requests"));assertEquals(0,value.failure());assertFalse(result.isDone());
                    assertFalse(BrokerProcess.absent(before.getInt("pid"),before.getLong("start_ticks")));
                    assertFalse(before.getBoolean("binder_dead"));
                    if(mode.equals("death")) {
                        android.os.Process.killProcess(before.getInt("pid"));long end=SystemClock.elapsedRealtime()+1000;
                        while(value.failure()==0 && SystemClock.elapsedRealtime()<end)Thread.sleep(5);
                        assertEquals(RuntimeResult.WORKER_DIED,value.failure());
                    }else if(mode.equals("cancel"))value.close();
                    else {assertEquals(0,value.failure());assertFalse(value.evidence().getBoolean("binder_dead"));}
                }finally {release.countDown();}
                try {result.get(5,TimeUnit.SECONDS);fail("Provisional RPC failure returned success");}
                catch(ExecutionException failure) {assertTrue(failure.getCause() instanceof ChildNetworkGate.Failure);assertEquals(code,((ChildNetworkGate.Failure)failure.getCause()).code);}
                JSONObject proof=retired(value,mode.equals("cancel")?0:code);
                if(mode.equals("cancel"))assertEquals("OWNER_CLOSE",proof.getString("terminal_origin"));
                if(mode.equals("ipc")) {assertEquals("REPLY_RPC",proof.getString("terminal_origin"));assertEquals("PROTOCOL",proof.getString("terminal_category"));}
                rows.put(new JSONObject().put("mode",mode).put("attempt",attempt).put("returned",code).put("before",before).put("retired",proof).put("fresh",fresh()));
            }
        }finally {caller.shutdownNow();assertTrue(caller.awaitTermination(5,TimeUnit.SECONDS));}
        record("race",new JSONObject().put("actual_tls",true).put("actual_binder",true).put("rows",rows));
    }
    private static String stat(String state,long start) {
        return statFlags(state,start,"0");
    }
    private static String statFlags(String state,long start,String flags) {
        String[] f=new String[22];Arrays.fill(f,"0");f[0]=state;f[6]=flags;f[11]="2";f[12]="3";f[19]=Long.toString(start);return "123 (owned) "+String.join(" ",f);
    }
    private static BrokerProcess.ProcReader transition(String before,String status,String after) {
        AtomicInteger reads=new AtomicInteger();return (path,max)->path.endsWith("/status")?status:reads.getAndIncrement()==0?before:after;
    }
    private static void rejected(BrokerProcess.ProcReader reader) throws Exception {
        try {BrokerProcess.absent(123,7,reader);fail("Unknown process observation was accepted as absence");}
        catch(IOException | ErrnoException expected) { }
    }
    @Test public void missingRssRequiresConfirmedDyingIdentityAndZombieNeverProvesAbsence() throws Exception {
        BrokerProcess observed=BrokerProcess.read(123,transition(stat("R",7),"Name:\towned\n",stat("Z",7)));
        assertTrue(observed.dying());assertEquals(0,observed.rss);assertEquals(7,observed.start);
        assertFalse(BrokerProcess.absent(123,7,transition(stat("R",7),"Name:\towned\n",stat("Z",7))));
        assertFalse(BrokerProcess.absent(123,7,transition(stat("Z",7),"unused",stat("Z",7))));
        // exit_signals sets PF_EXITING before exit_mm removes VmRSS and before state Z.
        BrokerProcess exiting=BrokerProcess.read(123,transition(stat("R",7),"Name:\towned\n",statFlags("R",7,"4")));
        assertTrue(exiting.exiting());assertTrue(exiting.dying());assertEquals("R",exiting.state);assertEquals(0,exiting.rss);
        assertFalse(BrokerProcess.absent(123,7,transition(stat("R",7),"Name:\towned\n",statFlags("R",7,"4"))));
        assertFalse(BrokerProcess.absent(123,7,transition(statFlags("R",7,"4"),"unused",statFlags("R",7,"4"))));
        for(String flags:new String[]{"-4","4294967296","invalid"})rejected(transition(statFlags("R",7,flags),"unused",stat("Z",7)));
        rejected(transition(statFlags("R",7,"4"),"unused",stat("R",7)));
        rejected(transition(stat("R",7),"Name:\towned\n",stat("R",7)));
        rejected(transition(stat("R",7),"VmRSS:\t2 MB\n",stat("Z",7)));
        rejected(transition(stat("R",7),"VmRSS:\t2 kB\nVmRSS:\t3 kB\n",stat("Z",7)));
        rejected(transition(stat("R",7),"Name:\towned\n",stat("Z",8)));
        rejected(transition(stat("R",7),"Name:\towned\n",stat("?",7)));
        rejected(transition(stat("R",7),"VmRSS:\t2 kB\n","malformed"));
        rejected((path,max)->{throw new ErrnoException("read",OsConstants.EACCES);});
        assertTrue(BrokerProcess.absent(123,7,(path,max)->{throw new ErrnoException("read",OsConstants.ENOENT);}));
        assertTrue(BrokerProcess.absent(123,7,(path,max)->{throw new ErrnoException("read",OsConstants.ESRCH);}));
        assertTrue(BrokerProcess.absent(123,7,transition(stat("R",8),"VmRSS:\t2 kB\n",stat("R",8))));
        record("kernel",new JSONObject().put("simulated_parser_only",true).put("dying_missing_rss",true).put("zombie_not_absent",true)
            .put("unknown_refused",true).put("malformed_refused",true).put("access_denied_refused",true).put("pid_change_refused",true)
            .put("actual_absence_errno_only",true).put("pid_reuse",true).put("exiting_flag_before_zombie",true)
            .put("exiting_not_absent",true).put("invalid_flags_refused",true).put("exit_regression_refused",true));
    }
    @After public void cleanup() throws Exception {
        try {for(RemoteHttpsTransport value:owned) {value.close();long end=SystemClock.elapsedRealtime()+5000;
            while(!value.terminated() && SystemClock.elapsedRealtime()<end)Thread.sleep(10);assertTrue(value.terminated());}}
        finally {server.shutdown();}
    }
}
