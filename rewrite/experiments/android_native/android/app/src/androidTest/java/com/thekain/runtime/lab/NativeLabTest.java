package com.thekain.runtime.lab;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Executes source-built Android ELF through JNI; all HTTPS data/certificates are owned. */
@RunWith(AndroidJUnit4.class)
public class NativeLabTest {
    private Context context;
    private MockWebServer server;
    private String url;
    @Before public void setup() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File home=NativeRuntime.prepare(context);
        File fixtures=new File(home,"fixtures");assertTrue(fixtures.mkdirs() || fixtures.isDirectory());
        HeldCertificate cert=new HeldCertificate.Builder().commonName("owned fixture").addSubjectAlternativeName("127.0.0.1").build();
        HeldCertificate wrong=new HeldCertificate.Builder().commonName("unrelated fixture").build();
        try(FileOutputStream out=new FileOutputStream(new File(fixtures,"owned-ca.pem"))) { out.write(cert.certificatePem().getBytes(StandardCharsets.US_ASCII)); }
        try(FileOutputStream out=new FileOutputStream(new File(fixtures,"wrong-ca.pem"))) { out.write(wrong.certificatePem().getBytes(StandardCharsets.US_ASCII)); }
        server=new MockWebServer();
        server.useHttps(new HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setHeader("Content-Type","text/html").setBody(
                    "<html><title>Owned native fixture</title><video controls><source src='/media.mp4?token=fixture-only' type='video/mp4; codecs=\"avc1.42E01E, mp4a.40.2\"'></video></html>");
            }
        });
        server.start(InetAddress.getByName("127.0.0.1"),0);
        url="https://127.0.0.1:"+server.getPort()+"/video";
    }
    @After public void cleanup() throws Exception { if(server!=null) server.shutdown(); }

    @Test public void sourceBuiltInterpreterAndTlsLoadOnActualPageSize() throws Exception {
        JSONObject report=NativeRuntime.dispatch(context,new JSONObject().put("op","probe"));
        assertEquals("PASS",report.getString("status"));
        assertEquals("[3,14,8]",report.getJSONArray("python").toString());
        assertEquals("android",report.getString("platform"));
        assertTrue(report.getBoolean("installed_native_extensions"));
        assertEquals(0,report.getInt("mimalloc"));
        assertTrue(report.getString("openssl").startsWith("OpenSSL 3.5.9"));
        assertEquals(Os.sysconf(OsConstants._SC_PAGESIZE),report.getLong("page_size"));
    }
    @Test public void realYtDlpAnalyzesOwnedTlsPageWithoutInventingTracksOrDownloading() throws Exception {
        int before=server.getRequestCount();
        JSONObject report=NativeRuntime.dispatch(context,new JSONObject().put("op","analyze").put("url",url).put("ca","owned-ca.pem"));
        assertEquals("PASS",report.getString("status"));
        assertEquals(1,report.getInt("formats"));
        assertFalse(report.getBoolean("download_enabled"));assertFalse(report.getBoolean("eligible"));
        assertTrue(report.isNull("video_codec"));assertEquals("mp4a.40.2",report.getString("audio_codec"));
        assertEquals("TRACKS_UNKNOWN",report.getString("reason"));
        assertEquals(before+1,server.getRequestCount());
        RecordedRequest request=server.takeRequest(5,TimeUnit.SECONDS);
        assertNotNull(request);assertEquals("GET",request.getMethod());assertEquals("/video",request.getPath());
    }
    @Test public void untrustedFixtureCertificateIsRejected() throws Exception {
        JSONObject report=NativeRuntime.dispatch(context,new JSONObject().put("op","analyze").put("url",url).put("ca","wrong-ca.pem"));
        assertEquals("FAIL",report.getString("status"));assertEquals("TLS_ERROR",report.getString("error_code"));
        assertFalse(report.toString().contains(url));
    }
    @Test public void externalProgramExecutionIsRefused() throws Exception {
        JSONObject report=NativeRuntime.dispatch(context,new JSONObject().put("op","block_exec"));
        assertEquals("PASS",report.getString("status"));assertTrue(report.getBoolean("external_exec_blocked"));
    }
}
