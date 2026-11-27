package com.thekain.runtime.lab;

import android.content.Context;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import okhttp3.mockwebserver.*;
import okhttp3.tls.*;
import okio.Buffer;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** New transfer gates; no original analysis, UID or product tests are replaced. */
@RunWith(AndroidJUnit4.class)
public final class RemoteDownloadTest {
    private Context context;private MockWebServer server;private String origin;
    private RemoteHttpsTransport.Policy policy;
    private final List<RemoteHttpsTransport> owned=new ArrayList<>();
    private final List<JSONObject> proofs=new ArrayList<>();
    private final byte[] body=new byte[2*1024*1024+17];
    @Before public void setup() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        for(int i=0;i<body.length;i++)body[i]=(byte)(i%251);
        HeldCertificate certificate=new HeldCertificate.Builder().commonName("owned download").addSubjectAlternativeName("127.0.0.1").build();
        server=new MockWebServer();server.useHttps(new HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(),false);
        server.start(InetAddress.getByName("127.0.0.1"),0);origin="https://127.0.0.1:"+server.getPort();
        policy=RemoteHttpsTransport.Policy.fixture(origin,certificate.certificate().getEncoded());
        assertTrue(RemoteHttpsTransport.recover(context)<=1);
    }
    private RemoteHttpsTransport create(RemoteHttpsTransport.Policy p,String url,long budget) throws Exception {
        RemoteHttpsTransport t=RemoteHttpsTransport.forDownload(context,p,url,budget);owned.add(t);return t;
    }
    private void retire(RemoteHttpsTransport t) throws Exception {
        t.close();long end=SystemClock.elapsedRealtime()+3000;
        while(!t.terminated() && SystemClock.elapsedRealtime()<end)Thread.sleep(10);
        assertTrue("Actual download worker failed to retire",t.terminated());JSONObject proof=t.evidence();
        assertTrue(proof.getBoolean("transfer_only"));assertTrue(proof.getBoolean("kernel_process_gone"));
        assertTrue(proof.getBoolean("binder_dead"));assertTrue(proof.getBoolean("service_absent"));assertTrue(proof.getBoolean("lease_clean"));
        assertFalse(proof.getBoolean("owner_python_loaded"));assertTrue(proof.getLong("retire_ms")<=2500);
        for(JSONObject old:proofs)assertNotEquals(old.getString("instance"),proof.getString("instance"));
        proofs.add(proof);
    }
    private BrokerDownload copy(RemoteHttpsTransport t,ByteArrayOutputStream sink) throws Exception {
        try(RemoteHttpsTransport.DownloadSession session=t.openDownload()) {
            byte[] buffer=new byte[32768];int count;
            while((count=session.read(buffer))>=0)sink.write(buffer,0,count);
            return session.receipt();
        }
    }
    private void fresh() throws Exception {
        server.enqueue(new MockResponse().setBody("fresh").setHeader("Content-Type","video/mp4"));
        RemoteHttpsTransport t=create(policy,origin+"/fresh.mp4",10000);
        try {ByteArrayOutputStream out=new ByteArrayOutputStream();assertEquals(0,copy(t,out).error);assertEquals("fresh",out.toString("US-ASCII"));}
        finally {retire(t);}
    }
    private void record(String name,JSONObject data) throws Exception {
        JSONArray rows=new JSONArray();for(JSONObject proof:proofs)rows.put(proof);
        data.put("case",name).put("source_commit",BuildConfig.QA_SOURCE_COMMIT).put("api",Build.VERSION.SDK_INT)
            .put("pages",android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)).put("proofs",rows).put("production_ready",false);
        File folder=new File(context.getFilesDir(),"remote-download-evidence");assertTrue(folder.mkdirs() || folder.isDirectory());
        try(FileOutputStream out=new FileOutputStream(new File(folder,name+".json"))) {out.write(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
    @After public void cleanup() throws Exception {
        try {for(RemoteHttpsTransport t:owned) {t.close();long end=SystemClock.elapsedRealtime()+3000;while(!t.terminated() && SystemClock.elapsedRealtime()<end)Thread.sleep(10);assertTrue(t.terminated());}}
        finally {server.shutdown();}
    }
    @Test public void boundedPipeStreamsBeyondAnalysisCapWithExactBytesAndReceipt() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type","video/mp4").setBody(new Buffer().write(body)));
        RemoteHttpsTransport t=create(policy,origin+"/large.mp4",30000);
        try {ByteArrayOutputStream out=new ByteArrayOutputStream();BrokerDownload result=copy(t,out);
            assertEquals(0,result.error);assertEquals(body.length,result.bytes);assertEquals(body.length,result.declaredBytes);assertArrayEquals(body,out.toByteArray());
            String cap=t.evidence().getString("id");
            assertEquals(RuntimeResult.OUT_OF_SCOPE,t.endpointForTests().fetch(cap,1,origin+"/analysis","GET").error);
            assertNotEquals(0,t.endpointForTests().read(cap,1,0).error);
            assertEquals(1,server.getRequestCount());
        }finally {retire(t);}
        long retiredAt=t.evidence().getLong("retire_ms");
        fresh();assertEquals("A fresh operation rewrote the previous retirement measurement",retiredAt,t.evidence().getLong("retire_ms"));
        record("stream",new JSONObject().put("bytes",body.length).put("exact_bytes",true).put("bounded_pipe",true).put("fresh",true).put("history_immutable",true));
    }
    @Test public void redirectTlsAndFramingLimitsRefuseBeforePublicationAndAllowFreshWorker() throws Exception {
        List<MockResponse> cases=Arrays.asList(
            new MockResponse().setResponseCode(302).setHeader("Location","https://127.0.0.1:1/private"),
            new MockResponse().setChunkedBody("body",1),
            new MockResponse().setBody("body").addHeader("Content-Length",4),
            new MockResponse().setHeader("Content-Length",BrokerDownload.MAX_BYTES+1),
            new MockResponse().setBody(new Buffer().write(body)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            new MockResponse().setResponseCode(403),new MockResponse().setResponseCode(404),new MockResponse().setResponseCode(429).setHeader("Retry-After","86401"),
            new MockResponse().setResponseCode(302).setHeader("Location",origin+"/ungranted.mp4"));
        int index=0;
        for(MockResponse response:cases) {
            server.enqueue(response);RemoteHttpsTransport t=create(policy,origin+"/negative.mp4",10000);
            try {BrokerDownload receipt=copy(t,new ByteArrayOutputStream());assertNotEquals(0,receipt.error);
                if(index>=5 && index<=7)assertEquals(new int[]{403,404,429}[index-5],receipt.httpStatus);
            }finally {retire(t);}
            index++;fresh();
        }
        HeldCertificate wrong=new HeldCertificate.Builder().commonName("wrong CA").build();
        RemoteHttpsTransport t=create(RemoteHttpsTransport.Policy.fixture(origin,wrong.certificate().getEncoded()),origin+"/tls.mp4",10000);
        try {assertEquals(RuntimeResult.TLS_ERROR,copy(t,new ByteArrayOutputStream()).error);}finally {retire(t);}
        fresh();record("security",new JSONObject().put("scope_redirect",true).put("framing",true).put("size",true).put("truncation",true).put("http_errors",true).put("tls",true).put("fresh_after_each",true));
    }
    @Test public void realStuckDnsCancelAndDeadlineRequireFailureAndActualProcessDeath() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            for(boolean cancel:new boolean[]{true,false}) {
                RemoteHttpsTransport.Policy stuck=RemoteHttpsTransport.Policy.publicOrigins(Collections.singleton("https://owned.example.org")).fault(1,RuntimeRequest.MAX_RSS,10000);
                RemoteHttpsTransport t=create(stuck,"https://owned.example.org/file.mp4",cancel?10000:5000);
                Future<?> result=executor.submit(()->copy(t,new ByteArrayOutputStream()));
                assertTrue(t.lookupSignal().get(5,TimeUnit.SECONDS));
                if(cancel)t.close();
                try {result.get(7,TimeUnit.SECONDS);fail("Missing/EOF result cannot be a successful deadline");}catch(ExecutionException actualFailure) {assertNotNull(actualFailure.getCause());}
                if(!cancel)assertEquals(RuntimeResult.TIMEOUT,t.failure());
                retire(t);fresh();
            }
        }finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
        record("lifecycle",new JSONObject().put("actual_stuck_dns",true).put("cancel",true).put("deadline",true).put("explicit_failure",true).put("fresh_after_each",true));
    }
    @Test public void capabilityAndWritableFileDescriptorsCannotReplaceThePipe() throws Exception {
        RemoteHttpsTransport t=create(policy,origin+"/fd.mp4",10000);
        File target=new File(context.getCacheDir(),"download-refuse-"+UUID.randomUUID());
        try {
            long end=SystemClock.elapsedRealtime()+5000;
            while(!t.evidence().getString("handshake_phase").equals("READY") && SystemClock.elapsedRealtime()<end)Thread.sleep(10);
            // Bad capabilities must not cause a network request or consume the one transfer grant.
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
            try {assertEquals(RuntimeResult.OUT_OF_SCOPE,t.endpointForTests().download(UUID.randomUUID().toString(),origin+"/escape",pipe[1]).error);}
            finally {pipe[0].close();pipe[1].close();}
        }finally {retire(t);}
        t=create(policy,origin+"/fd.mp4",10000);
        try {
            // Wait only for authenticated configuration, without starting a network transfer.
            long end=SystemClock.elapsedRealtime()+5000;
            while(!t.evidence().getString("handshake_phase").equals("READY") && SystemClock.elapsedRealtime()<end)Thread.sleep(10);
            try(ParcelFileDescriptor file=ParcelFileDescriptor.open(target,ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_WRITE_ONLY)) {
                assertEquals(RuntimeResult.INVALID_REQUEST,t.endpointForTests().download(t.evidence().getString("id"),origin+"/fd.mp4",file).error);
            }
            assertEquals(0,target.length());
        }finally {retire(t);assertTrue(target.delete());}
        // An analysis grant cannot request the larger streaming limit.
        RuntimeRequest request=new RuntimeRequest(UUID.randomUUID().toString(),RuntimeRequest.SOURCE_ANALYZE,origin+"/analysis",1,SystemClock.elapsedRealtime()+10000,RuntimeRequest.MAX_RSS,RuntimeRequest.MAX_HEADROOM);
        RemoteHttpsTransport analysis=(RemoteHttpsTransport)policy.create(context,request,(count,bytes)->{});owned.add(analysis);
        try {
            long end=SystemClock.elapsedRealtime()+5000;while(!analysis.evidence().getString("handshake_phase").equals("READY") && SystemClock.elapsedRealtime()<end)Thread.sleep(10);
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
            try {assertEquals(RuntimeResult.OUT_OF_SCOPE,analysis.endpointForTests().download(request.id,origin+"/analysis",pipe[1]).error);}
            finally {pipe[0].close();pipe[1].close();}
        }finally {analysis.close();long end=SystemClock.elapsedRealtime()+3000;while(!analysis.terminated() && SystemClock.elapsedRealtime()<end)Thread.sleep(10);assertTrue(analysis.terminated());}
        record("boundary",new JSONObject().put("capability",true).put("file_fd_refused",true).put("analysis_grant_refused",true));
    }
}
