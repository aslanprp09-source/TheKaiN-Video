package com.thekain.runtime.lab;

import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Additional tests; the original 23 + 4 same-UID tests are unchanged. */
@RunWith(AndroidJUnit4.class)
public final class IsolatedRuntimeTest extends RuntimeTestSupport {
    private IsolatedRuntime isolated;
    @Before public void isolatedSetup() {isolated=new IsolatedRuntime(context);}
    @After public void isolatedCleanup() {if(isolated!=null)isolated.close();}
    private IsolatedRuntime.Outcome finish(IsolatedRuntime.Operation op) throws Exception {
        IsolatedRuntime.Outcome out=op.completion().get(35,TimeUnit.SECONDS);
        assertTrue("Confirmed OS retirement, error="+out.result.error,out.deathConfirmed);
        File[] leftovers=context.getCacheDir().listFiles((folder,name)->name.startsWith("isolated-python-") || name.startsWith("isolated-fixture-"));
        assertNotNull(leftovers);assertEquals("No retained IPC resource files",0,leftovers.length);
        assertTrue("Owner canary must exist and stay unchanged",out.ownerCanaryIntact);healthyMain();return out;
    }
    private IsolatedRuntime.Operation submit(int op,String address,int mode,long timeout) {
        return isolated.submit(op,address,RuntimeRequest.CA_OWN,timeout,RuntimeRequest.MAX_RSS,mode);
    }
    private IsolatedRuntime.Outcome execute(int op,String address) throws Exception {return finish(submit(op,address,0,30000));}
    private void identity(IsolatedRuntime.Outcome out) {
        assertTrue(out.workerPid>0);assertNotEquals(parentPid,out.workerPid);
        assertNotEquals(android.os.Process.myUid(),out.workerUid);assertTrue(out.workerUid%100000>=90000);
        assertTrue(out.observerPid>0);assertNotEquals(parentPid,out.observerPid);assertNotEquals(out.workerPid,out.observerPid);
        assertNotEquals(android.os.Process.myUid(),out.observerUid);assertEquals(out.workerUid,out.observerUid);assertTrue(out.observerNonDumpable);assertEquals(255,out.childProtection);
        assertTrue(out.observerUid%100000>=90000);assertTrue(out.observerStartTicks>0);
        assertTrue(out.observerPeakRssBytes>0);assertTrue(out.observerCpuTicks>0);assertFalse(out.observerPythonLoaded);
        assertTrue(out.startTicks>0);assertTrue(out.samples>0);assertTrue(out.peakRssBytes>0);
        assertTrue(out.observedCpuMs>0);assertTrue(out.cpuLimit>0);
        assertEquals(out.virtualBefore+RuntimeRequest.MAX_HEADROOM,out.addressLimit);
    }
    private IsolatedRuntime.Outcome recovery(IsolatedRuntime.Outcome previous) throws Exception {
        IsolatedRuntime.Outcome next=execute(RuntimeRequest.ANALYZE,url);assertEquals(0,next.result.error);
        assertEquals(1,next.result.pythonCalls);assertEquals(1,next.result.formats);identity(next);
        assertNotEquals(previous.instance,next.instance);assertNotEquals(previous.result.id,next.result.id);return next;
    }
    @Test public void separateUidInterpreterSealedResourcesAndOpenSsl() throws Exception {
        IsolatedRuntime.Outcome out=execute(RuntimeRequest.PROBE,null);assertEquals(0,out.result.error);identity(out);
        assertEquals(31,out.securityDenials);assertEquals("[3,14,8]",out.result.python);
        assertTrue(out.result.opensslHandshake);assertTrue(out.result.installedExtensions);assertEquals(1,out.result.pythonCalls);
        assertEquals(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE),out.result.pages);
        assertEquals(64,out.result.pointerBits);assertEquals(android.os.Build.SUPPORTED_ABIS[0].equals("arm64-v8a")?"aarch64":"x86_64",out.result.machine);
    }
    @Test public void realOwnedHttpsAnalysisGoesOnlyThroughOwnerBroker() throws Exception {
        int count=server.getRequestCount();IsolatedRuntime.Outcome out=execute(RuntimeRequest.ANALYZE,url);
        assertEquals(0,out.result.error);identity(out);assertEquals(1,out.result.formats);
        assertEquals(count+1,server.getRequestCount());assertEquals(1,out.requests);assertTrue(out.payloadBytes>0);
        assertFalse(out.result.downloadEnabled);assertFalse(out.result.eligible);assertNull(out.result.videoCodec);
        assertEquals("mp4a.40.2",out.result.audioCodec);
    }
    @Test public void cancelBlockedHttpsReadRetiresOwnerTransportBeforeFreshOperation() throws Exception {
        int count=server.getRequestCount();IsolatedRuntime.Operation op=submit(RuntimeRequest.ANALYZE,url.replace("/video","/stall"),0,30000);
        op.whenPythonStarted().get(25,TimeUnit.SECONDS);
        long until=SystemClock.elapsedRealtime()+5000;
        while(server.getRequestCount()==count && SystemClock.elapsedRealtime()<until)Thread.sleep(10);
        assertTrue("Owned server must observe the blocked HTTP request",server.getRequestCount()>count);
        assertTrue(op.cancel());IsolatedRuntime.Outcome out=finish(op);
        assertEquals(RuntimeResult.CANCELED,out.result.error);assertTrue(out.cancelToExitMs<=3000);recovery(out);
    }
    @Test public void repeatedLegacyWorkerKillsRetainTypedDeathAndFreshRecovery() throws Exception {
        for(int i=0;i<3;i++) {
            NativeRuntime.Operation op=runtime.submit(RuntimeRequest.HANG,null,1,30000);
            int pid=op.whenPythonStarted().get(25,TimeUnit.SECONDS);ProcessIdentity.worker(pid).killOwned();
            NativeRuntime.Outcome out=await(op);assertEquals(RuntimeResult.WORKER_DIED,out.result.error);
            assertWorkerRetired(out);assertFresh(out);
        }
    }
    @Test public void pythonCannotReadOrModifyOwnerFilesOrOpenNetwork() throws Exception {
        IsolatedRuntime.Outcome out=finish(submit(RuntimeRequest.PROBE,null,IsolatedInput.SECURITY,30000));
        assertEquals(0,out.result.error);assertEquals(255,out.securityDenials);identity(out);
        String canary=ProcessIdentity.read(new File(context.getFilesDir(),"isolated-owner-canary").getPath(),128);
        assertEquals("owner-only-"+out.result.id,canary);
    }
    @Test public void cancelReapsEvenWhenWorkerMainAndPythonAreBlocked() throws Exception {
        IsolatedRuntime.Operation op=submit(RuntimeRequest.HANG,null,IsolatedInput.BLOCK_MAIN,30000);
        op.whenPythonStarted().get(25,TimeUnit.SECONDS);healthyMain();assertTrue(op.cancel());
        IsolatedRuntime.Outcome out=finish(op);assertEquals(RuntimeResult.CANCELED,out.result.error);
        assertTrue(out.cancelToExitMs>=0 && out.cancelToExitMs<=3000);identity(out);recovery(out);
    }
    @Test public void globalDeadlineReapsBlockedWorkerAndAllowsFreshAnalysis() throws Exception {
        IsolatedRuntime.Outcome out=finish(submit(RuntimeRequest.HANG,null,IsolatedInput.BLOCK_MAIN,12000));
        assertEquals(RuntimeResult.TIMEOUT,out.result.error);assertTrue(out.pythonEntered);identity(out);recovery(out);
    }
    @Test public void nativeAbortCannotCrashOwnerAndFreshAnalysisWorks() throws Exception {
        IsolatedRuntime.Outcome out=execute(RuntimeRequest.CRASH,null);assertEquals(RuntimeResult.WORKER_DIED,out.result.error);recovery(out);
    }
    @Test public void residentMemoryThresholdIsKernelSampledOutsideWorker() throws Exception {
        IsolatedRuntime.Outcome out=finish(isolated.submit(RuntimeRequest.MEMORY,null,1,30000,256L*1024*1024,0));
        assertEquals(RuntimeResult.MEMORY_LIMIT,out.result.error);identity(out);recovery(out);
    }
    @Test public void lowResidentBudgetForcesIndependentSupervisorKillAndFreshRecovery() throws Exception {
        long limit=64L*1024*1024;
        IsolatedRuntime.Outcome out=finish(isolated.submit(RuntimeRequest.MEMORY,null,1,30000,limit,0));
        assertEquals(RuntimeResult.MEMORY_LIMIT,out.result.error);assertTrue(out.pythonEntered);identity(out);
        assertTrue("Independent kernel sample must actually exceed RSS budget",out.peakRssBytes>limit);
        recovery(out);
    }
    @Test public void oversizedNativeChildFrameCannotAllocateInSupervisorAndFreshRecoveryWorks() throws Exception {
        IsolatedRuntime.Outcome out=finish(submit(RuntimeRequest.PROBE,null,IsolatedInput.CHILD_FRAME,30000));
        assertEquals(RuntimeResult.IPC_ERROR,out.result.error);assertTrue(out.pythonEntered);identity(out);recovery(out);
    }
    @Test public void platformCredentialSyscallKillsOnlyNativeChildAndFreshAnalysisRecovers() throws Exception {
        IsolatedRuntime.Outcome out=finish(submit(RuntimeRequest.PROBE,null,IsolatedInput.PLATFORM_CREDENTIAL,30000));
        assertEquals(RuntimeResult.WORKER_DIED,out.result.error);assertFalse(out.pythonEntered);
        assertEquals(android.system.OsConstants.SIGSYS,out.workerExitSignal);recovery(out);
    }
    @Test public void secondClientCannotShareAnInterpreter() throws Exception {
        IsolatedRuntime.Operation first=submit(RuntimeRequest.HANG,null,0,30000);
        first.whenPythonStarted().get(25,TimeUnit.SECONDS);
        try(IsolatedRuntime other=new IsolatedRuntime(context)) {
            assertEquals(RuntimeResult.BUSY,other.submit(RuntimeRequest.PROBE,null,1,30000).completion().get(2,TimeUnit.SECONDS).result.error);
        }
        assertTrue(first.cancel());IsolatedRuntime.Outcome out=finish(first);assertEquals(1,out.result.error);recovery(out);
    }
    @Test public void forgedProcDescriptorFailsBeforePythonAndRecovers() throws Exception {
        IsolatedRuntime.Outcome out=finish(submit(RuntimeRequest.PROBE,null,IsolatedInput.FAKE_PROC,30000));
        assertEquals(RuntimeResult.IPC_ERROR,out.result.error);assertFalse(out.pythonEntered);recovery(out);
    }
    @Test public void tamperedResourceDigestFailsBeforePythonAndRecovers() throws Exception {
        IsolatedRuntime.Outcome out=finish(submit(RuntimeRequest.PROBE,null,IsolatedInput.BAD_DIGEST,30000));
        assertEquals(RuntimeResult.INVALID_REQUEST,out.result.error);assertFalse(out.pythonEntered);recovery(out);
    }
    @Test public void wrongOperationResultCannotCompleteNextOperation() throws Exception {
        IsolatedRuntime.Outcome out=execute(RuntimeRequest.BAD_REPLY,null);assertEquals(RuntimeResult.IPC_ERROR,out.result.error);recovery(out);
    }
    @Test public void invalidCapabilityMethodsAndDestinationsConsumeNoNetworkBudget() throws Exception {
        int count=server.getRequestCount();IsolatedRuntime.Outcome out=finish(submit(RuntimeRequest.ANALYZE,url,IsolatedInput.IPC_FLOOD,30000));
        assertEquals(0,out.result.error);assertEquals(1,out.requests);assertEquals(count+1,server.getRequestCount());identity(out);
    }
    @Test public void requestFloodIsBoundedInOwner() throws Exception {
        IsolatedRuntime.Outcome out=execute(RuntimeRequest.REQUEST_FLOOD,url);assertEquals(RuntimeResult.REQUEST_LIMIT,out.result.error);assertEquals(12,out.requests);recovery(out);
    }
    @Test public void responseAndAggregateBytesAreBoundedInOwner() throws Exception {
        IsolatedRuntime.Outcome large=execute(RuntimeRequest.ANALYZE,url.replace("/video","/large"));
        assertEquals(RuntimeResult.RESPONSE_LIMIT,large.result.error);assertEquals(0,large.payloadBytes);
        IsolatedRuntime.Outcome total=execute(RuntimeRequest.BYTE_FLOOD,url.replace("/video","/bytes"));
        assertEquals(RuntimeResult.RESPONSE_LIMIT,total.result.error);assertTrue(total.payloadBytes<=ChildNetworkGate.MAX_TOTAL+1);recovery(total);
    }
    @Test public void wrongCaAndRedirectEscapeRemainRejected() throws Exception {
        IsolatedRuntime.Outcome ca=finish(isolated.submit(RuntimeRequest.ANALYZE,url,RuntimeRequest.CA_WRONG,30000));
        assertEquals(RuntimeResult.TLS_ERROR,ca.result.error);
        IsolatedRuntime.Outcome redirect=execute(RuntimeRequest.ANALYZE,url.replace("/video","/escape"));
        assertEquals(RuntimeResult.OUT_OF_SCOPE,redirect.result.error);assertEquals(1,redirect.requests);recovery(redirect);
    }
    @Test public void outputBoundsAndExecRefusalRemainEnforced() throws Exception {
        IsolatedRuntime.Outcome out=execute(RuntimeRequest.OUTPUT_FLOOD,null);assertEquals(RuntimeResult.OUTPUT_LIMIT,out.result.error);
        IsolatedRuntime.Outcome exec=execute(RuntimeRequest.BLOCK_EXEC,null);assertEquals(0,exec.result.error);assertTrue(exec.result.externalExecBlocked);identity(exec);
    }
    @Test public void privateServiceManifestAndMainJniGuard() throws Exception {
        ServiceInfo info=context.getPackageManager().getServiceInfo(new ComponentName(context,IsolatedRuntimeService.class),0);
        assertFalse(info.exported);assertTrue((info.flags&ServiceInfo.FLAG_ISOLATED_PROCESS)!=0);
        assertEquals(IsolatedRuntimeService.NAME,info.processName);
        try {Class.forName("com.thekain.runtime.lab.SupervisorNative");fail("JNI in owner");}
        catch(ExceptionInInitializerError expected) {assertTrue(expected.getCause() instanceof IllegalStateException);}
        healthyMain();
    }
    @Test public void oversizedAndMalformedReplyCannotAllocateOrAuthorizeTransport() {
        Parcel p=Parcel.obtain();
        try {p.writeInt(0);p.writeInt(Integer.MAX_VALUE);p.setDataPosition(0);
            try {IsolatedReply.CREATOR.createFromParcel(p);fail("oversized payload");}catch(BadParcelableException expected) { }
        }finally {p.recycle();}
        for(String address:new String[]{"http://127.0.0.1:1/","https://example.com/","file:///etc/passwd","https://user@127.0.0.1:1/"}) {
            try {submit(RuntimeRequest.ANALYZE,address,0,30000);fail(address);}catch(BadParcelableException expected) { }
        }
    }
    @Test public void closeCancelsSpinAndLateResultCannotWin() throws Exception {
        IsolatedRuntime.Operation op=submit(RuntimeRequest.HANG,null,0,30000);op.whenPythonStarted().get(25,TimeUnit.SECONDS);
        isolated.close();IsolatedRuntime.Outcome out=finish(op);assertEquals(1,out.result.error);assertFalse(op.cancel());
        try(IsolatedRuntime next=new IsolatedRuntime(context)) {
            IsolatedRuntime.Outcome fresh=next.submit(RuntimeRequest.ANALYZE,url,1,30000).completion().get(35,TimeUnit.SECONDS);
            assertEquals(0,fresh.result.error);assertTrue(fresh.deathConfirmed);assertNotEquals(out.instance,fresh.instance);
        }
    }
    @Test public void lostOwnerLeaseCannotBeForgottenByANewSupervisor() throws Exception {
        IsolatedLease lease=new IsolatedLease(context);assertFalse(lease.file.getBaseFile().exists());
        lease.reserve("00000000-0000-0000-0000-000000000000");
        try {
            org.json.JSONObject marker=new org.json.JSONObject(ProcessIdentity.read(lease.file.getBaseFile().getPath(),2048));
            marker.put("owner_pid",parentPid+1);
            try(FileOutputStream out=new FileOutputStream(lease.file.getBaseFile())) {out.write(marker.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            IsolatedRuntime.Outcome blocked=isolated.submit(RuntimeRequest.PROBE,null,1,30000).completion().get(2,TimeUnit.SECONDS);
            assertEquals(RuntimeResult.CLEANUP_FAILED,blocked.result.error);assertFalse(blocked.deathConfirmed);
            assertEquals(0,blocked.workerPid);assertFalse(blocked.pythonEntered);healthyMain();
        }finally {lease.cleared();} // Synthetic test marker only; no worker was bound.
    }
}
