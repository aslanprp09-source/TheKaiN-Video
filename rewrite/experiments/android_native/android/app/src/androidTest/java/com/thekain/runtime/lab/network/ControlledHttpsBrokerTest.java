package com.thekain.runtime.lab.network;

import android.content.Context;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.thekain.runtime.lab.BuildConfig;
import okhttp3.mockwebserver.*;
import okhttp3.tls.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static com.thekain.runtime.lab.network.BrokerFailure.Code.*;

/** Existing native/UID gates remain unchanged; these are separate owner-network component tests. */
@RunWith(AndroidJUnit4.class)
public final class ControlledHttpsBrokerTest {
    private MockWebServer server;
    private HandshakeCertificates trust;
    private InetAddress loopback;
    private String origin;
    private Context context;
    private long started,startedCpu,startedPss;
    private final ControlledHttpsBroker.Limits ordinary=new ControlledHttpsBroker.Limits(10000,12,512*1024,2*1024*1024,16384,4);
    interface Work {void run() throws Exception;}
    private void fails(BrokerFailure.Code code,Work work) throws Exception {
        try {work.run();fail("Expected "+code);}catch(BrokerFailure expected) {assertEquals(code,expected.code);
            assertFalse(expected.toString().contains("fixture-sensitive"));assertNull(expected.getCause());}
    }
    @Before public void setup() throws Exception {
        started=SystemClock.elapsedRealtime();startedCpu=android.os.Process.getElapsedCpuTime();startedPss=Debug.getPss();
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();loopback=InetAddress.getByAddress(new byte[]{127,0,0,1});
        HeldCertificate cert=new HeldCertificate.Builder().commonName("owned.test").addSubjectAlternativeName("owned.test").build();
        trust=new HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate()).build();
        server=new MockWebServer();server.useHttps(new HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false);
        server.start(loopback,0);origin="https://owned.test:"+server.getPort();
    }
    @After public void cleanup() throws Exception {server.shutdown();}
    private ControlledHttpsBroker fixture(ControlledHttpsBroker.Limits limits,ControlledHttpsBroker.Resolver resolver) throws Exception {
        return new ControlledHttpsBroker(PublicHttpsPolicy.fixture(origin,loopback),resolver,limits,trust.sslSocketFactory(),trust.trustManager());
    }
    private ControlledHttpsBroker fixture(ControlledHttpsBroker.Limits limits) throws Exception {return fixture(limits,host->Collections.singletonList(loopback));}
    private void record(String name,JSONObject facts) throws Exception {
        File folder=new File(context.getFilesDir(),"broker-evidence");assertTrue(folder.mkdirs() || folder.isDirectory());
        facts.put("source_commit",BuildConfig.QA_SOURCE_COMMIT).put("case",name).put("api",Build.VERSION.SDK_INT)
            .put("page_size",android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
            .put("elapsed_ms",SystemClock.elapsedRealtime()-started).put("process_cpu_delta_ms",android.os.Process.getElapsedCpuTime()-startedCpu)
            .put("pss_start_kib",startedPss).put("pss_end_kib",Debug.getPss()).put("measurement_scope","instrumented_process_including_owned_fixture_not_peak")
            .put("production_ready",false).put("product_integration",false);
        try(FileOutputStream out=new FileOutputStream(new File(folder,name+".json"))) {out.write(facts.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
    @Test public void publicPolicyRejectsPrivateMixedAndChangedPeersBeforeConnect() throws Exception {
        PublicHttpsPolicy policy=new PublicHttpsPolicy(Collections.singleton("https://owned.example.org"));
        InetAddress publicIp=InetAddress.getByAddress(new byte[]{8,8,8,8});
        for(List<InetAddress> answer:Arrays.asList(Collections.singletonList(loopback),Arrays.asList(publicIp,loopback),
                Collections.singletonList(InetAddress.getByName("fd00:ec2::254")),Collections.singletonList(InetAddress.getByName("169.254.169.254")))) {
            ControlledHttpsBroker broker=new ControlledHttpsBroker(policy,host->answer,ordinary,null,null);
            try(ControlledHttpsBroker.Operation op=broker.operation()) {
                fails(ADDRESS_DENIED,()->op.fetch("https://owned.example.org/video?token=fixture-sensitive","GET"));
                assertEquals(0,op.connectionAttempts());assertTrue(op.isQuiescent());
            }
        }
        PublicHttpsPolicy.Pin pin=policy.pin(policy.target("https://owned.example.org/video"),Collections.singletonList(publicIp));
        fails(ADDRESS_DENIED,()->PublicHttpsPolicy.peer(pin,new InetSocketAddress(loopback,443)));
        fails(ADDRESS_DENIED,()->PublicHttpsPolicy.peer(pin,InetSocketAddress.createUnresolved("owned.example.org",443)));
        assertEquals(0,server.getRequestCount());
        record("address",new JSONObject().put("public_policy",true).put("mixed_rejected",true).put("peer_rejected",true).put("connections",0));
    }
    @Test public void fixturePinsOneLookupAndRejectsRedirectRebinding() throws Exception {
        AtomicInteger lookups=new AtomicInteger();
        ControlledHttpsBroker broker=fixture(ordinary,host->Collections.singletonList(lookups.incrementAndGet()==1?loopback:InetAddress.getByName("169.254.169.254")));
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location","/next"));
        try(ControlledHttpsBroker.Operation op=broker.operation()) {
            fails(ADDRESS_DENIED,()->op.fetch(origin+"/first","GET"));
            assertEquals(2,lookups.get());assertEquals(1,op.connectionAttempts());assertEquals(1,server.getRequestCount());assertTrue(op.isQuiescent());
        }
        AtomicInteger single=new AtomicInteger();
        ControlledHttpsBroker stable=fixture(ordinary,host->Collections.singletonList(single.incrementAndGet()==1?loopback:InetAddress.getByName("10.0.0.1")));
        server.enqueue(new MockResponse().setBody("pinned"));
        try(ControlledHttpsBroker.Operation op=stable.operation()) {
            assertArrayEquals("pinned".getBytes(java.nio.charset.StandardCharsets.UTF_8),op.fetch(origin+"/once","GET").body());
            assertEquals(1,single.get());assertEquals(1,op.connectionAttempts());assertTrue(op.isQuiescent());
        }
        record("pinning",new JSONObject().put("fixture_only",true).put("single_lookup",true).put("rebind_rejected",true).put("redirect_connections",1));
    }
    @Test public void tlsCredentialsAndDowngradeRemainRejected() throws Exception {
        ControlledHttpsBroker wrongCa=new ControlledHttpsBroker(PublicHttpsPolicy.fixture(origin,loopback),host->Collections.singletonList(loopback),ordinary,null,null);
        try(ControlledHttpsBroker.Operation op=wrongCa.operation()) {fails(TLS_ERROR,()->op.fetch(origin+"/ca","GET"));assertTrue(op.isQuiescent());}
        String other="https://other.test:"+server.getPort();
        ControlledHttpsBroker wrongHost=new ControlledHttpsBroker(PublicHttpsPolicy.fixture(other,loopback),host->Collections.singletonList(loopback),ordinary,trust.sslSocketFactory(),trust.trustManager());
        try(ControlledHttpsBroker.Operation op=wrongHost.operation()) {fails(TLS_ERROR,()->op.fetch(other+"/host","GET"));assertTrue(op.isQuiescent());}
        assertEquals(0,server.getRequestCount());
        try(ControlledHttpsBroker.Operation op=fixture(ordinary).operation()) {
            fails(INVALID_URL,()->op.fetch("https://user:fixture-sensitive@owned.test:"+server.getPort()+"/video","GET"));assertEquals(0,op.connectionAttempts());
        }
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location","http://127.0.0.1/private"));
        try(ControlledHttpsBroker.Operation op=fixture(ordinary).operation()) {fails(INVALID_URL,()->op.fetch(origin+"/down","GET"));assertEquals(1,op.connectionAttempts());}
        assertEquals(1,server.getRequestCount());
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location","https://unapproved.example.org/video"));
        try(ControlledHttpsBroker.Operation op=fixture(ordinary).operation()) {fails(OUT_OF_SCOPE,()->op.fetch(origin+"/changed-origin","GET"));assertEquals(1,op.connectionAttempts());}
        assertEquals(2,server.getRequestCount());
        record("tls",new JSONObject().put("fixture_only",true).put("wrong_ca_rejected",true).put("wrong_host_rejected",true)
            .put("credentials_rejected",true).put("downgrade_rejected",true));
    }
    private static void until(Callable<Boolean> ready) throws Exception {
        long end=SystemClock.elapsedRealtime()+5000;while(!ready.call() && SystemClock.elapsedRealtime()<end)Thread.sleep(10);assertTrue(ready.call());
    }
    private void failedFuture(Future<?> future,BrokerFailure.Code code) throws Exception {
        try {future.get(1500,TimeUnit.MILLISECONDS);fail("Expected "+code);}catch(ExecutionException expected) {
            assertTrue(expected.getCause() instanceof BrokerFailure);assertEquals(code,((BrokerFailure)expected.getCause()).code);
        }
    }
    @Test public void cancelDeadlineAndStuckDnsHaveBoundedActualQuiescence() throws Exception {
        ExecutorService callers=Executors.newFixedThreadPool(2);
        try {
            server.enqueue(new MockResponse().setBody("delayed").setBodyDelay(3,TimeUnit.SECONDS));
            try(ControlledHttpsBroker.Operation op=fixture(ordinary).operation()) {
                Future<?> result=callers.submit(()->op.fetch(origin+"/stall","GET"));assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));
                Thread.sleep(100);long before=SystemClock.elapsedRealtime();op.close();failedFuture(result,CANCELED);
                assertTrue(SystemClock.elapsedRealtime()-before<1500);assertTrue(op.isQuiescent());assertEquals(0,op.payloadBytesRead());
            }
            server.enqueue(new MockResponse().setBody("late").setBodyDelay(3,TimeUnit.SECONDS));
            try(ControlledHttpsBroker.Operation op=fixture(new ControlledHttpsBroker.Limits(300,12,1024,2048,1024,2)).operation()) {
                long before=SystemClock.elapsedRealtime();fails(TIMEOUT,()->op.fetch(origin+"/deadline","GET"));
                assertTrue(SystemClock.elapsedRealtime()-before<1500);assertEquals(0,op.payloadBytesRead());assertTrue(op.isQuiescent());
            }
            for(boolean cancel:new boolean[]{true,false}) {
                CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
                ControlledHttpsBroker broker=new ControlledHttpsBroker(new PublicHttpsPolicy(Collections.singleton("https://owned.example.org")),host->{
                    entered.countDown();while(true)try {release.await();break;}catch(InterruptedException ignored) { }
                    return Collections.singletonList(InetAddress.getByName("8.8.8.8"));
                },new ControlledHttpsBroker.Limits(cancel?10000:300,12,1024,2048,1024,2),null,null);
                try(ControlledHttpsBroker.Operation op=broker.operation()) {
                    try {
                        Future<?> result=callers.submit(()->op.fetch("https://owned.example.org/dns","GET"));assertTrue(entered.await(5,TimeUnit.SECONDS));
                        if(cancel)op.close();failedFuture(result,cancel?CANCELED:TIMEOUT);
                        assertFalse("Canceled Future is not proof that DNS work ended",op.isQuiescent());assertEquals(0,op.connectionAttempts());
                    } finally {op.close();release.countDown();until(op::isQuiescent);}
                    assertEquals(0,op.connectionAttempts());
                }
            }
            CountDownLatch both=new CountDownLatch(2),release=new CountDownLatch(1);
            ControlledHttpsBroker saturated=new ControlledHttpsBroker(new PublicHttpsPolicy(Collections.singleton("https://owned.example.org")),host->{
                both.countDown();while(true)try {release.await();break;}catch(InterruptedException ignored) { }
                return Collections.singletonList(InetAddress.getByName("8.8.8.8"));
            },ordinary,null,null);
            try(ControlledHttpsBroker.Operation first=saturated.operation();ControlledHttpsBroker.Operation second=saturated.operation()) {
                try {
                    Future<?> a=callers.submit(()->first.fetch("https://owned.example.org/a","GET"));
                    Future<?> b=callers.submit(()->second.fetch("https://owned.example.org/b","GET"));
                    assertTrue(both.await(5,TimeUnit.SECONDS));first.close();second.close();failedFuture(a,CANCELED);failedFuture(b,CANCELED);
                    assertFalse(first.isQuiescent());assertFalse(second.isQuiescent());
                    try(ControlledHttpsBroker.Operation third=saturated.operation()) {fails(DNS_BUSY,()->third.fetch("https://owned.example.org/c","GET"));assertEquals(0,third.connectionAttempts());}
                } finally {first.close();second.close();release.countDown();until(()->first.isQuiescent() && second.isQuiescent());}
            }
        } finally {callers.shutdownNow();assertTrue(callers.awaitTermination(5,TimeUnit.SECONDS));}
        record("cancellation",new JSONObject().put("fixture_only",true).put("body_cancel",true).put("dns_cancel",true)
            .put("dns_deadline",true).put("late_result_refused",true).put("dns_threads_bounded",2).put("actual_quiescence_checked",true));
    }
    @Test public void requestHeaderBodyAndAggregateLimitsAreEnforcedWithoutCredentials() throws Exception {
        ControlledHttpsBroker.Limits small=new ControlledHttpsBroker.Limits(10000,12,64,96,1024,2);
        server.enqueue(new MockResponse().setBody(new String(new char[128]).replace('\0','x')));
        try(ControlledHttpsBroker.Operation op=fixture(small).operation()) {fails(RESPONSE_LIMIT,()->op.fetch(origin+"/known","GET"));assertEquals(0,op.payloadBytesRead());}
        RecordedRequest headers=server.takeRequest(5,TimeUnit.SECONDS);assertNotNull(headers);
        assertEquals("identity",headers.getHeader("Accept-Encoding"));assertEquals("close",headers.getHeader("Connection"));
        assertNull(headers.getHeader("Cookie"));assertNull(headers.getHeader("Authorization"));assertNull(headers.getHeader("Proxy-Authorization"));
        server.enqueue(new MockResponse().setChunkedBody(new String(new char[128]).replace('\0','x'),7));
        try(ControlledHttpsBroker.Operation op=fixture(small).operation()) {fails(UNSUPPORTED_FRAMING,()->op.fetch(origin+"/chunked","GET"));assertEquals(0,op.payloadBytesRead());}
        server.enqueue(new MockResponse().setBody("unknown").removeHeader("Content-Length").setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
        try(ControlledHttpsBroker.Operation op=fixture(small).operation()) {fails(UNSUPPORTED_FRAMING,()->op.fetch(origin+"/unknown","GET"));assertEquals(0,op.payloadBytesRead());}
        server.enqueue(new MockResponse().setBody(new String(new char[64]).replace('\0','x')).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        try(ControlledHttpsBroker.Operation op=fixture(small).operation()) {fails(NETWORK_LOST,()->op.fetch(origin+"/truncated","GET"));assertTrue(op.payloadBytesRead()>0 && op.payloadBytesRead()<64);}
        server.enqueue(new MockResponse().setBody(new String(new char[64]).replace('\0','x')));
        server.enqueue(new MockResponse().setBody(new String(new char[64]).replace('\0','x')));
        try(ControlledHttpsBroker.Operation op=fixture(small).operation()) {
            assertEquals(64,op.fetch(origin+"/one","GET").body().length);
            fails(RESPONSE_LIMIT,()->op.fetch(origin+"/two","GET"));assertEquals(64,op.payloadBytesRead());
        }
        server.enqueue(new MockResponse().setHeader("X-Owned",new String(new char[2048]).replace('\0','x')));
        try(ControlledHttpsBroker.Operation op=fixture(small).operation()) {fails(HEADER_LIMIT,()->op.fetch(origin+"/headers","GET"));}
        server.enqueue(new MockResponse().setHeader("Content-Encoding","gzip").setBody("owned"));
        try(ControlledHttpsBroker.Operation op=fixture(small).operation()) {fails(UNSUPPORTED_ENCODING,()->op.fetch(origin+"/encoding","GET"));}
        server.enqueue(new MockResponse().addHeader("Content-Encoding","gzip").addHeader("Content-Encoding","identity").setBody("owned"));
        try(ControlledHttpsBroker.Operation op=fixture(small).operation()) {fails(UNSUPPORTED_ENCODING,()->op.fetch(origin+"/duplicate-encoding","GET"));}
        server.enqueue(new MockResponse().setBody("owned").addHeader("Content-Length","5"));
        try(ControlledHttpsBroker.Operation op=fixture(small).operation()) {fails(UNSUPPORTED_FRAMING,()->op.fetch(origin+"/duplicate-length","GET"));assertEquals(0,op.payloadBytesRead());}
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location","/hop"));
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location","/hop"));
        try(ControlledHttpsBroker.Operation op=fixture(new ControlledHttpsBroker.Limits(10000,2,64,96,1024,4)).operation()) {
            fails(REQUEST_LIMIT,()->op.fetch(origin+"/hop?token=fixture-sensitive","GET"));assertEquals(2,op.requestCount());assertEquals(2,op.connectionAttempts());
        }
        try(ControlledHttpsBroker.Operation op=fixture(new ControlledHttpsBroker.Limits(10000,2,64,96,1024,4)).operation()) {
            fails(INVALID_URL,()->op.fetch("file:///private","GET"));
            fails(INVALID_URL,()->op.fetch(origin+"/invalid","POST"));
            fails(REQUEST_LIMIT,()->op.fetch(origin+"/over","GET"));assertEquals(0,op.connectionAttempts());
        }
        record("limits",new JSONObject().put("fixture_only",true).put("body_limit",true).put("header_limit",true)
            .put("aggregate_limit",true).put("request_limit",true).put("identity_only",true).put("credentials_absent",true)
            .put("chunked_refused_before_body",true).put("unknown_length_refused",true).put("truncation_refused",true));
    }
    @Test public void ownedPublicFixtureUsesStrictPolicySystemTlsAndExactBytes() throws Exception {
        String url="https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/24d8b6e0183d994b161d76842914f91344e9af3d/rewrite/android/app/src/androidTest/assets/owned-sample.mp4";
        ControlledHttpsBroker broker=new ControlledHttpsBroker(Collections.singleton("https://raw.githubusercontent.com"),ControlledHttpsBroker.Limits.laboratory());
        byte[] expected;
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("owned-sample.mp4");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int count;while((count=in.read(buffer))!=-1) {out.write(buffer,0,count);assertTrue(out.size()<=65536);}expected=out.toByteArray();
        }
        try(ControlledHttpsBroker.Operation op=broker.operation()) {
            byte[] actual=op.fetch(url,"GET").body();assertArrayEquals(expected,actual);assertEquals(1,op.requestCount());assertTrue(op.connectionAttempts()>0);
            assertEquals(expected.length,op.payloadBytesRead());assertTrue(op.isQuiescent());
            StringBuilder sha=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(actual))sha.append(String.format(Locale.ROOT,"%02x",value&255));
            record("public",new JSONObject().put("public_policy",true).put("system_tls",true).put("owned_fixture",true)
                .put("bytes",actual.length).put("sha256",sha.toString()).put("requests",op.requestCount()));
        }catch(BrokerFailure failed) {
            record("public-failure",new JSONObject().put("code",failed.code.name()).put("http_status",failed.httpStatus)
                .put("classification",failed.code==HTTP_ERROR && (failed.httpStatus==429 || failed.httpStatus>=500)?"UPSTREAM_STATUS":"NETWORK_OR_POLICY_UNCONFIRMED"));
            throw failed; // No skip/fallback or false green on an unavailable live fixture.
        }
    }
}
