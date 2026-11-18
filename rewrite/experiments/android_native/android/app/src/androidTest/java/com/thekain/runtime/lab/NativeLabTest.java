package com.thekain.runtime.lab;

import android.system.Os;
import android.system.OsConstants;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Original four checks retained; Python now executes only in the worker process. */
@RunWith(AndroidJUnit4.class)
public class NativeLabTest extends RuntimeTestSupport {
    @Test public void sourceBuiltInterpreterAndTlsLoadOnActualPageSize() throws Exception {
        NativeRuntime.Outcome out=probe();RuntimeResult report=out.result;
        assertEquals("[3,14,8]",report.python);assertEquals("android",report.platform);
        assertTrue(report.installedExtensions);assertEquals(0,report.mimalloc);
        assertTrue(report.openssl.startsWith("OpenSSL 3.5.9"));
        assertEquals(Os.sysconf(OsConstants._SC_PAGESIZE),report.pages);
        assertEquals(out.virtualBefore+RuntimeRequest.MAX_HEADROOM,out.addressLimit);
        assertTrue(out.cpuLimit>0);assertTrue(report.opensslHandshake);
    }
    @Test public void realYtDlpAnalyzesOwnedTlsPageWithoutInventingTracksOrDownloading() throws Exception {
        int before=server.getRequestCount();
        NativeRuntime.Outcome out=run(RuntimeRequest.ANALYZE,url,RuntimeRequest.CA_OWN);RuntimeResult report=out.result;
        assertEquals(RuntimeResult.OK,report.error);assertEquals(1,report.formats);
        assertFalse(report.downloadEnabled);assertFalse(report.eligible);assertNull(report.videoCodec);
        assertEquals("mp4a.40.2",report.audioCodec);assertEquals(RuntimeResult.TRACKS_UNKNOWN,report.reason);
        assertEquals(before+1,server.getRequestCount());assertEquals(1,out.requests);assertTrue(out.networkBytes>0);
        RecordedRequest request=server.takeRequest(5,TimeUnit.SECONDS);
        assertNotNull(request);assertEquals("GET",request.getMethod());assertEquals("/video",request.getPath());
        assertWorkerRetired(out);
    }
    @Test public void untrustedFixtureCertificateIsRejected() throws Exception {
        NativeRuntime.Outcome out=run(RuntimeRequest.ANALYZE,url,RuntimeRequest.CA_WRONG);
        assertEquals(RuntimeResult.TLS_ERROR,out.result.error);assertEquals(1,out.requests);assertEquals(0,out.networkBytes);
        assertWorkerRetired(out);
    }
    @Test public void externalProgramExecutionIsRefused() throws Exception {
        NativeRuntime.Outcome out=run(RuntimeRequest.BLOCK_EXEC,null,RuntimeRequest.CA_OWN);
        assertEquals(RuntimeResult.OK,out.result.error);assertTrue(out.result.externalExecBlocked);assertWorkerRetired(out);
    }
}
