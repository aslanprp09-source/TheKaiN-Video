package com.thekain.runtime.lab;

import android.content.Context;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import okhttp3.mockwebserver.*;
import okhttp3.tls.*;
import org.junit.*;
import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public abstract class RuntimeTestSupport {
    Context context;
    NativeRuntime runtime;
    MockWebServer server;
    String url;
    int parentPid;
    @Before public void setupRuntime() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();parentPid=android.os.Process.myPid();
        File home=RuntimeAssets.prepare(context),fixtures=new File(home,"fixtures");
        assertTrue(fixtures.mkdirs() || fixtures.isDirectory());
        HeldCertificate cert=new HeldCertificate.Builder().commonName("owned fixture").addSubjectAlternativeName("127.0.0.1").build();
        HeldCertificate wrong=new HeldCertificate.Builder().commonName("unrelated fixture").build();
        write(new File(fixtures,"owned-ca.pem"),cert.certificatePem());
        write(new File(fixtures,"owned-key.pem"),cert.privateKeyPkcs8Pem());
        write(new File(fixtures,"wrong-ca.pem"),wrong.certificatePem());
        server=new MockWebServer();
        server.useHttps(new HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if("/stall".equals(request.getPath()))return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
                if("/large".equals(request.getPath()))return new MockResponse().setHeader("Content-Type","text/html").setBody(repeat(600*1024));
                if("/bytes".equals(request.getPath()))return new MockResponse().setHeader("Content-Type","text/html").setBody(repeat(400*1024));
                if("/escape".equals(request.getPath()))return new MockResponse().setResponseCode(302).setHeader("Location","https://127.0.0.1:1/forbidden");
                return new MockResponse().setHeader("Content-Type","text/html").setBody(
                    "<html><title>Owned native fixture</title><video controls><source src='/media.mp4?token=fixture-only' type='video/mp4; codecs=\"avc1.42E01E, mp4a.40.2\"'></video></html>");
            }
        });
        server.start(InetAddress.getByName("127.0.0.1"),0);url="https://127.0.0.1:"+server.getPort()+"/video";
        // Establish fixture readiness before the timed worker operation, without an HTTP request.
        HandshakeCertificates trust=new HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate()).build();
        try(java.net.Socket tcp=new java.net.Socket()) {
            tcp.connect(new java.net.InetSocketAddress("127.0.0.1",server.getPort()),5000);
            try(javax.net.ssl.SSLSocket tls=(javax.net.ssl.SSLSocket)trust.sslSocketFactory()
                    .createSocket(tcp,"127.0.0.1",server.getPort(),true)) {
                tls.setSoTimeout(5000);javax.net.ssl.SSLParameters parameters=tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);tls.startHandshake();
            }
        }
        runtime=new NativeRuntime(context);healthyMain();
    }
    private static String repeat(int count) { char[] chars=new char[count];java.util.Arrays.fill(chars,'x');return new String(chars); }
    private static void write(File file,String value) throws IOException {
        try(FileOutputStream out=new FileOutputStream(file)) { out.write(value.getBytes(StandardCharsets.US_ASCII)); }
    }
    @After public void cleanupRuntime() throws Exception { if(runtime!=null)runtime.close();if(server!=null)server.shutdown(); }
    NativeRuntime.Outcome await(NativeRuntime.Operation operation) throws Exception { return operation.completion().get(35,TimeUnit.SECONDS); }
    NativeRuntime.Outcome run(int operation,String address,int ca) throws Exception {
        return await(runtime.submit(operation,address,ca,30000));
    }
    NativeRuntime.Outcome probe() throws Exception {
        NativeRuntime.Outcome out=run(RuntimeRequest.PROBE,null,RuntimeRequest.CA_OWN);
        assertEquals(RuntimeResult.OK,out.result.error);assertEquals(1,out.result.pythonCalls);assertTrue(out.result.opensslHandshake);
        assertWorkerRetired(out);return out;
    }
    void assertWorkerRetired(NativeRuntime.Outcome out) throws Exception {
        assertEquals(parentPid,out.parentPid);assertNotEquals(parentPid,out.workerPid);assertTrue(out.workerPid>0);
        assertTrue(out.deathConfirmed);assertTrue(out.startTicks>0);assertFalse(out.instance.isEmpty());
        assertTrue(out.samples>0);assertTrue(out.peakRssBytes>0);healthyMain();
    }
    void healthyMain() throws Exception {
        assertEquals(parentPid,android.os.Process.myPid());
        assertFalse(ProcessIdentity.read("/proc/self/maps",2*1024*1024).contains("libpython3.14.so"));
        CountDownLatch tick=new CountDownLatch(1);new Handler(Looper.getMainLooper()).post(tick::countDown);
        assertTrue("Main Looper must remain responsive",tick.await(2,TimeUnit.SECONDS));
    }
    void assertFresh(NativeRuntime.Outcome before) throws Exception {
        NativeRuntime.Outcome after=run(RuntimeRequest.ANALYZE,url,RuntimeRequest.CA_OWN);
        assertEquals(RuntimeResult.OK,after.result.error);assertEquals(1,after.result.pythonCalls);
        assertEquals(1,after.result.formats);assertFalse(after.result.downloadEnabled);
        assertFalse(after.result.eligible);assertWorkerRetired(after);
        assertNotEquals(before.result.id,after.result.id);
        assertNotEquals(before.instance,after.instance);
    }
}
