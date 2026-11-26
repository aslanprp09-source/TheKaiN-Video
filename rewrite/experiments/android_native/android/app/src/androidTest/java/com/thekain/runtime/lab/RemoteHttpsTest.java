package com.thekain.runtime.lab;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import okhttp3.mockwebserver.*;
import okhttp3.tls.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real ordinary-UID network PID, typed IPC, irreversible retirement and restart. Original73 stay mandatory. */
@RunWith(AndroidJUnit4.class)
public final class RemoteHttpsTest {
    private Context context;private MockWebServer server;private String origin;private RemoteHttpsTransport.Policy fixture;
    private final List<RemoteHttpsTransport> owned=new ArrayList<>();
    private final List<JSONObject> proofs=new ArrayList<>();
    private int priorPid;private String priorInstance;
    @Before public void setup() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        HeldCertificate certificate=new HeldCertificate.Builder().commonName("owned fixture").addSubjectAlternativeName("127.0.0.1").build();
        server=new MockWebServer();server.useHttps(new HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(),false);
        server.setDispatcher(new Dispatcher() {
            public MockResponse dispatch(RecordedRequest request) {
                if(request.getPath().startsWith("/escape"))return new MockResponse().setResponseCode(302).setHeader("Location","https://127.0.0.1:1/private");
                int size=request.getPath().startsWith("/large")?600*1024:request.getPath().startsWith("/chunks")?100*1024:5;
                return new MockResponse().setHeader("Content-Type","text/html").setBody(new String(new char[size]).replace('\0','x'));
            }
        });
        server.start(InetAddress.getByName("127.0.0.1"),0);origin="https://127.0.0.1:"+server.getPort();
        fixture=RemoteHttpsTransport.Policy.fixture(origin,certificate.certificate().getEncoded());
        assertEquals(IsolatedLease.NO_PENDING,RemoteHttpsTransport.recover(context));
    }
    private RemoteHttpsTransport create(RemoteHttpsTransport.Policy policy,String url,long timeout) throws Exception {
        RuntimeRequest request=new RuntimeRequest(UUID.randomUUID().toString(),RuntimeRequest.SOURCE_ANALYZE,url,1,SystemClock.elapsedRealtime()+timeout,RuntimeRequest.MAX_RSS,RuntimeRequest.MAX_HEADROOM);
        RemoteHttpsTransport value=(RemoteHttpsTransport)policy.create(context,request,(count,bytes)->{});owned.add(value);return value;
    }
    private static void until(Callable<Boolean> ready) throws Exception {
        long end=SystemClock.elapsedRealtime()+5000;while(!ready.call() && SystemClock.elapsedRealtime()<end)Thread.sleep(10);assertTrue(ready.call());
    }
    private void retire(RemoteHttpsTransport value) throws Exception {
        value.close();until(value::terminated);JSONObject proof=value.evidence();
        assertTrue(proof.getBoolean("kernel_process_gone"));assertTrue(proof.getBoolean("binder_dead"));assertTrue(proof.getBoolean("lease_clean"));
        assertFalse(proof.getBoolean("owner_python_loaded"));assertTrue(proof.getLong("retire_ms")<=2500);
        int pid=proof.getInt("pid");assertNotEquals(android.os.Process.myPid(),pid);assertTrue(pid>0);
        if(priorInstance!=null)assertNotEquals(priorInstance,proof.getString("instance"));priorPid=pid;priorInstance=proof.getString("instance");
        assertTrue(BrokerProcess.absent(pid,proof.getLong("start_ticks")));proofs.add(proof);
        assertFalse(new File(context.getFilesDir(),"https-pending.json").exists());
        String[] guards=context.getFilesDir().list((dir,name)->name.startsWith("https-lease-"));assertNotNull(guards);assertEquals(0,guards.length);
    }
    private void fresh() throws Exception {
        RemoteHttpsTransport value=create(fixture,origin+"/fresh",10000);
        try {assertEquals(5,value.reply(origin+"/fresh","GET").body.length);}finally {retire(value);}
    }
    private void record(String name,JSONObject details) throws Exception {
        JSONArray rows=new JSONArray();for(JSONObject proof:proofs)rows.put(proof);
        details.put("case",name).put("proofs",rows).put("source_commit",BuildConfig.QA_SOURCE_COMMIT).put("api",Build.VERSION.SDK_INT)
            .put("page_size",android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)).put("production_ready",false);
        File folder=new File(context.getFilesDir(),"remote-https-evidence");assertTrue(folder.mkdirs() || folder.isDirectory());
        try(FileOutputStream out=new FileOutputStream(new File(folder,name+".json"))) {out.write(details.toString().getBytes(StandardCharsets.UTF_8));}
    }
    interface Work {void run() throws Exception;}
    private void failed(int code,Work work) throws Exception {
        try {work.run();fail("Expected fixed failure "+code);}catch(ChildNetworkGate.Failure error) {assertEquals(code,error.code);assertNull(error.getCause());assertFalse(error.toString().contains("fixture-sensitive"));}
    }
    private void failedFuture(Future<?> result,int code) throws Exception {
        try {result.get(7,TimeUnit.SECONDS);fail("Missing fixed failure");}
        catch(ExecutionException e) {assertTrue(e.getCause() instanceof ChildNetworkGate.Failure);assertEquals(code,((ChildNetworkGate.Failure)e.getCause()).code);}
    }
    @After public void cleanup() throws Exception {
        try {for(RemoteHttpsTransport value:owned) {value.close();until(value::terminated);}}
        finally {server.shutdown();}
    }
    @Test public void boundedChunksCapabilitiesReplayAndRedirectCannotGrantExtraAccess() throws Exception {
        RemoteHttpsTransport value=create(fixture,origin+"/chunks",10000);
        try {
            IsolatedReply reply=value.reply(origin+"/chunks?token=fixture-sensitive","GET");assertEquals(100*1024,reply.body.length);
            assertEquals(200,reply.status);assertEquals(100*1024,reply.declaredBytes);assertEquals("text/html",reply.contentType);
            IRemoteHttps wire=value.endpointForTests();String id=value.evidence().getString("id");
            assertEquals(RuntimeResult.IPC_ERROR,wire.read(id,1,0).error);
            assertEquals(RuntimeResult.IPC_ERROR,wire.read(id,2,32768).error);
            assertEquals(RuntimeResult.IPC_ERROR,wire.fetch(UUID.randomUUID().toString(),2,origin+"/chunks","GET").error);
            assertEquals(1,server.getRequestCount());
            failed(RuntimeResult.OUT_OF_SCOPE,()->value.reply(origin+"/escape","GET"));assertEquals(2,server.getRequestCount());
            failed(RuntimeResult.RESPONSE_LIMIT,()->value.reply(origin+"/large","GET"));
        }finally {retire(value);}
        fresh();record("ipc",new JSONObject().put("chunk_size",BrokerChunk.CHUNK).put("capability_refused",true).put("replay_refused",true).put("redirect_refused",true).put("body_limit",true));
    }
    @Test public void stuckDnsCancelAndDeadlineKillTheActualProcessBeforeFreshTls() throws Exception {
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try {
            for(boolean cancel:new boolean[]{true,false}) {
                RemoteHttpsTransport.Policy policy=RemoteHttpsTransport.Policy.publicOrigins(Collections.singleton("https://owned.example.org")).fault(1,RuntimeRequest.MAX_RSS,10000);
                RemoteHttpsTransport value=create(policy,"https://owned.example.org/dns",cancel?10000:5000);
                Future<?> result=caller.submit(()->value.reply("https://owned.example.org/dns","GET"));
                assertTrue(value.lookupSignal().get(5,TimeUnit.SECONDS));
                if(cancel)value.close();failedFuture(result,cancel?RuntimeResult.CANCELED:RuntimeResult.TIMEOUT);
                retire(value);fresh();
            }
        }finally {caller.shutdownNow();assertTrue(caller.awaitTermination(5,TimeUnit.SECONDS));}
        record("dns",new JSONObject().put("actual_stuck_dns",true).put("cancel",true).put("deadline",true).put("fresh_after_each",true));
    }
    @Test public void crashAndMeasuredCpuRssViolationsRetireWithoutReplacingOwnerThreads() throws Exception {
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try {
            for(int kind:new int[]{2,3,0}) {
                RemoteHttpsTransport.Policy policy=fixture.fault(kind,kind==0?16L*1024*1024:RuntimeRequest.MAX_RSS,kind==3?1000:10000);
                RemoteHttpsTransport value=create(policy,origin+"/fault",10000);
                Future<?> result=caller.submit(()->value.reply(origin+"/fault","GET"));
                if(kind==3)assertTrue(value.cpuSignal().get(5,TimeUnit.SECONDS));
                failedFuture(result,kind==2?RuntimeResult.WORKER_DIED:kind==3?RuntimeResult.CPU_LIMIT:RuntimeResult.MEMORY_LIMIT);
                retire(value);JSONObject proof=value.evidence();
                if(kind==3)assertTrue(proof.getLong("observed_cpu_ms")>1000);
                if(kind==0)assertTrue(proof.getLong("peak_rss_bytes")>16L*1024*1024);
                fresh();
            }
        }finally {caller.shutdownNow();assertTrue(caller.awaitTermination(5,TimeUnit.SECONDS));}
        record("resources",new JSONObject().put("crash",true).put("cpu_measured",true).put("rss_measured",true).put("fresh_after_each",true));
    }
    @Test public void systemTlsPublicFixtureHasExactBytesAndNoFixtureAuthority() throws Exception {
        String url="https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/11fa103624275958f089d9566c7722fc64133094/rewrite/android/app/src/androidTest/assets/owned-sample.mp4";
        RemoteHttpsTransport value=create(RemoteHttpsTransport.Policy.publicOrigins(Collections.singleton("https://raw.githubusercontent.com")),url,30000);
        String digest;
        try {IsolatedReply reply=value.reply(url,"GET");assertEquals(2428,reply.body.length);assertEquals(2428,reply.declaredBytes);
            StringBuilder hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(reply.body))hex.append(String.format(Locale.ROOT,"%02x",b&255));digest=hex.toString();
            assertEquals("dd201a3b814b113ca98749902195b996a1e2f608247bdd22d2044b4c0dfd5cb2",digest);
        }finally {retire(value);}
        record("public",new JSONObject().put("system_tls",true).put("fixture_only",false).put("bytes",2428).put("sha256",digest));
    }
    @Test public void parcelAndManifestIntakeRejectUnboundedOrNonisolatedProductContracts() throws Exception {
        assertEquals(2048L,BrokerProcess.rssFromStatus("Name:\ttest\nVmRSS:\t2 kB\n"));
        for(String invalid:new String[]{"", "VmRSS: -1 kB", "VmRSS: 2 MB", "VmRSS: 2 kB\nVmRSS: 2 kB", "VmRSS: 9999999999999999 kB"}) {
            try {BrokerProcess.rssFromStatus(invalid);fail("Invalid RSS units/counters accepted");}catch(IOException expected) { }
        }
        ServiceInfo service=context.getPackageManager().getServiceInfo(new ComponentName(context,RemoteHttpsService.class),0);
        assertFalse(service.exported);assertEquals(context.getPackageName()+":https_broker",service.processName);
        assertEquals(0,service.flags&ServiceInfo.FLAG_ISOLATED_PROCESS);
        for(boolean config:new boolean[]{true,false}) {
            Parcel parcel=Parcel.obtain();
            try {
                if(config) {parcel.writeString(UUID.randomUUID().toString());parcel.writeLong(1);parcel.writeInt(Integer.MAX_VALUE);}
                else {parcel.writeInt(0);parcel.writeLong(1);parcel.writeInt(200);parcel.writeString("https://owned.example.org");parcel.writeString(null);parcel.writeLong(1);
                    parcel.writeInt(1);parcel.writeInt(0);parcel.writeInt(1);parcel.writeLong(0);parcel.writeInt(Integer.MAX_VALUE);}
                parcel.setDataPosition(0);try {if(config)BrokerConfig.CREATOR.createFromParcel(parcel);else BrokerChunk.CREATOR.createFromParcel(parcel);fail("Oversized IPC accepted");}
                catch(BadParcelableException expected) { }
            }finally {parcel.recycle();}
        }
        AnalysisMetadata.Format format=new AnalysisMetadata.Format("real",origin+"/owned.mp4","mp4",null,"mp4a.40.2",-1,0,0,0);
        AnalysisMetadata meta=new AnalysisMetadata("Owned","127.0.0.1",new AnalysisMetadata.Format[]{format});
        Parcel parcel=Parcel.obtain();try {meta.writeToParcel(parcel,0);assertTrue(parcel.dataSize()<16384);parcel.setDataPosition(0);
            AnalysisMetadata copy=AnalysisMetadata.CREATOR.createFromParcel(parcel);assertEquals(1,copy.formats().size());assertFalse(copy.toString().contains("owned.mp4"));}
        finally {parcel.recycle();}
        try {new AnalysisMetadata.Format("bad", "file:///etc/passwd", "mp4",null,null,-1,0,0,0);fail("File metadata accepted");}catch(BadParcelableException expected) { }
        record("schema",new JSONObject().put("oversize_rejected",true).put("typed_metadata",true).put("non_exported",true));
    }
}
