package com.thekain.runtime.lab;

import android.content.*;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RuntimeProcessTest extends RuntimeTestSupport {
    @Test public void hangingPythonCanBeCanceledWithoutBlockingMainAndNextAnalysisIsFresh() throws Exception {
        NativeRuntime.Operation op=runtime.submit(RuntimeRequest.HANG,null,RuntimeRequest.CA_OWN,30000);
        int pid=op.whenPythonStarted().get(20,TimeUnit.SECONDS);assertNotEquals(parentPid,pid);healthyMain();
        long start=SystemClock.elapsedRealtime();assertTrue(op.cancel());NativeRuntime.Outcome out=await(op);
        assertEquals(RuntimeResult.CANCELED,out.result.error);assertTrue(SystemClock.elapsedRealtime()-start<4000);
        assertWorkerRetired(out);assertFresh(out);
    }
    @Test public void totalDeadlineKillsUncooperativePythonAndDoesNotReuseItsProcess() throws Exception {
        NativeRuntime.Operation op=runtime.submit(RuntimeRequest.HANG,null,RuntimeRequest.CA_OWN,6000);
        op.whenPythonStarted().get(6,TimeUnit.SECONDS);healthyMain();NativeRuntime.Outcome out=await(op);
        assertEquals(RuntimeResult.TIMEOUT,out.result.error);assertTrue(out.elapsedMs>=6000 && out.elapsedMs<9000);
        assertWorkerRetired(out);assertFresh(out);
    }
    @Test public void actualNativeAbortDoesNotKillParentAndFreshInterpreterCanRun() throws Exception {
        NativeRuntime.Outcome out=run(RuntimeRequest.CRASH,null,RuntimeRequest.CA_OWN);
        assertEquals(RuntimeResult.WORKER_DIED,out.result.error);assertWorkerRetired(out);assertFresh(out);
    }
    @Test public void memoryIsBoundedByKernelAndExternalRssSupervisor() throws Exception {
        NativeRuntime.Operation op=runtime.submit(RuntimeRequest.MEMORY,null,RuntimeRequest.CA_OWN,30000,
            256L*1024*1024,RuntimeRequest.MAX_HEADROOM);
        op.whenPythonStarted().get(20,TimeUnit.SECONDS);NativeRuntime.Outcome out=await(op);
        assertEquals(RuntimeResult.MEMORY_LIMIT,out.result.error);assertTrue(out.samples>1);
        assertEquals(out.virtualBefore+RuntimeRequest.MAX_HEADROOM,out.addressLimit);
        assertWorkerRetired(out);assertFresh(out);
    }
    @Test public void wrongOperationIdOverRealBinderIsRejectedAndWorkerIsReaped() throws Exception {
        NativeRuntime.Outcome out=run(RuntimeRequest.BAD_REPLY,null,RuntimeRequest.CA_OWN);
        assertEquals(RuntimeResult.IPC_ERROR,out.result.error);assertWorkerRetired(out);assertFresh(out);
    }
    @Test public void parallelRequestsCannotStealOrMultiplyAnActiveWorker() throws Exception {
        NativeRuntime.Operation first=runtime.submit(RuntimeRequest.HANG,null,RuntimeRequest.CA_OWN,30000);
        first.whenPythonStarted().get(20,TimeUnit.SECONDS);
        try(NativeRuntime secondClient=new NativeRuntime(context)) {
            NativeRuntime.Outcome busy=await(secondClient.submit(RuntimeRequest.PROBE,null,RuntimeRequest.CA_OWN,30000));
            assertEquals(RuntimeResult.BUSY,busy.result.error);assertEquals(0,busy.workerPid);
        }
        healthyMain();assertTrue(first.cancel());NativeRuntime.Outcome out=await(first);
        assertEquals(RuntimeResult.CANCELED,out.result.error);assertFresh(out);
    }
    @Test public void immediateCancellationNeverReturnsAStaleSuccess() throws Exception {
        NativeRuntime.Operation op=runtime.submit(RuntimeRequest.PROBE,null,RuntimeRequest.CA_OWN,30000);
        assertTrue(op.cancel());NativeRuntime.Outcome out=await(op);
        assertEquals(RuntimeResult.CANCELED,out.result.error);assertTrue(out.deathConfirmed);probe();
    }
    @Test public void inputAndNativeOutputAreBoundedBeforeIpcDelivery() throws Exception {
        StringBuilder longUrl=new StringBuilder(url);while(longUrl.length()<2050)longUrl.append('x');
        NativeRuntime.Outcome invalid=run(RuntimeRequest.ANALYZE,longUrl.toString(),RuntimeRequest.CA_OWN);
        assertEquals(RuntimeResult.INVALID_REQUEST,invalid.result.error);assertEquals(0,invalid.workerPid);
        NativeRuntime.Outcome out=run(RuntimeRequest.OUTPUT_FLOOD,null,RuntimeRequest.CA_OWN);
        assertEquals(RuntimeResult.OUTPUT_LIMIT,out.result.error);assertWorkerRetired(out);assertFresh(out);
    }
    @Test public void javaRequestCounterStopsBeforeThirteenthRequest() throws Exception {
        NativeRuntime.Outcome out=run(RuntimeRequest.REQUEST_FLOOD,url,RuntimeRequest.CA_OWN);
        assertEquals(RuntimeResult.REQUEST_LIMIT,out.result.error);assertEquals(12,out.requests);
        assertEquals(12,server.getRequestCount());assertWorkerRetired(out);
    }
    @Test public void javaRejectsOversizedResponseBeforePythonParsesIt() throws Exception {
        NativeRuntime.Outcome out=run(RuntimeRequest.ANALYZE,url.replace("/video","/large"),RuntimeRequest.CA_OWN);
        assertEquals(RuntimeResult.RESPONSE_LIMIT,out.result.error);assertEquals(0,out.networkBytes);
        assertEquals(1,server.getRequestCount());assertWorkerRetired(out);
    }
    @Test public void aggregatePayloadBudgetIsEnforcedOutsidePython() throws Exception {
        NativeRuntime.Outcome out=run(RuntimeRequest.BYTE_FLOOD,url.replace("/video","/bytes"),RuntimeRequest.CA_OWN);
        assertEquals(RuntimeResult.RESPONSE_LIMIT,out.result.error);
        assertEquals(ChildNetworkGate.MAX_TOTAL+1,out.networkBytes);assertEquals(6,out.requests);assertWorkerRetired(out);
    }
    @Test public void redirectCannotEscapeOwnedHttpsOrigin() throws Exception {
        NativeRuntime.Outcome out=run(RuntimeRequest.ANALYZE,url.replace("/video","/escape"),RuntimeRequest.CA_OWN);
        assertEquals(RuntimeResult.OUT_OF_SCOPE,out.result.error);assertEquals(1,out.requests);assertWorkerRetired(out);
    }
    @Test public void directPythonSocketsAreDeniedWithKernelFenceActive() throws Exception {
        NativeRuntime.Outcome out=run(RuntimeRequest.DIRECT_SOCKET,null,RuntimeRequest.CA_OWN);
        assertEquals(RuntimeResult.OK,out.result.error);assertTrue(out.result.externalExecBlocked);
        assertTrue(out.addressLimit>0);assertWorkerRetired(out);
    }
    @Test public void closedSupervisorRejectsNewWork() throws Exception {
        runtime.close();NativeRuntime.Outcome out=run(RuntimeRequest.PROBE,null,RuntimeRequest.CA_OWN);
        assertEquals(RuntimeResult.BUSY,out.result.error);assertEquals(0,out.workerPid);healthyMain();
    }
    @Test public void invalidResourceBudgetCannotLaunchAChild() throws Exception {
        NativeRuntime.Outcome out=await(runtime.submit(RuntimeRequest.MEMORY,null,RuntimeRequest.CA_OWN,30000,
            RuntimeRequest.MAX_RSS,RuntimeRequest.MAX_HEADROOM+1));
        assertEquals(RuntimeResult.INVALID_REQUEST,out.result.error);assertEquals(0,out.workerPid);healthyMain();
    }
    @Test public void pidValidationRefusesTheMainProcessAndDirectJniLoad() throws Exception {
        try { ProcessIdentity.worker(parentPid);fail("Main PID must never be a kill target"); }
        catch(IOException expected) { }
        try { Class.forName("com.thekain.runtime.lab.ChildRuntime");fail("JNI must not initialize here"); }
        catch(ExceptionInInitializerError|NoClassDefFoundError expected) { }
        healthyMain();probe();
    }
    @Test public void manifestKeepsWorkerPrivateAndInDedicatedProcess() throws Exception {
        android.content.pm.ServiceInfo info=context.getPackageManager().getServiceInfo(
            new ComponentName(context,RuntimeService.class),0);
        assertFalse(info.exported);assertEquals(ProcessIdentity.NAME,info.processName);
        assertEquals(0,info.flags & android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS);
        assertEquals(context.getApplicationInfo().uid,info.applicationInfo.uid);
    }
    @Test public void closingClientTerminatesHungWorkerBeforeAnotherClientStarts() throws Exception {
        NativeRuntime.Operation op=runtime.submit(RuntimeRequest.HANG,null,RuntimeRequest.CA_OWN,30000);
        op.whenPythonStarted().get(20,TimeUnit.SECONDS);runtime.close();
        NativeRuntime.Outcome out=await(op);assertEquals(RuntimeResult.CANCELED,out.result.error);assertWorkerRetired(out);
        runtime=new NativeRuntime(context);assertFresh(out);
    }
    @Test public void malformedParcelOverBinderIsRejectedWithoutKillingParent() throws Exception {
        CountDownLatch connected=new CountDownLatch(1),dead=new CountDownLatch(1);
        AtomicReference<IBinder> remote=new AtomicReference<>();
        ServiceConnection connection=new ServiceConnection() {
            public void onServiceConnected(ComponentName name,IBinder binder) {remote.set(binder);connected.countDown();}
            public void onServiceDisconnected(ComponentName name) { }
        };
        assertTrue(context.bindService(new Intent(context,RuntimeService.class),connection,Context.BIND_AUTO_CREATE));
        try {
            assertTrue(connected.await(5,TimeUnit.SECONDS));IBinder binder=remote.get();binder.linkToDeath(dead::countDown,0);
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try {
                data.writeInterfaceToken("com.thekain.runtime.lab.IRuntimeWorker");data.writeInt(1);
                data.writeString(UUID.randomUUID().toString());data.writeInt(999);data.writeString(null);
                data.writeInt(RuntimeRequest.CA_OWN);data.writeLong(SystemClock.elapsedRealtime()+1000);
                data.writeLong(RuntimeRequest.MAX_RSS);data.writeLong(RuntimeRequest.MAX_HEADROOM);
                assertTrue(binder.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0));
                try {reply.readException();fail("Malformed operation must not be accepted");}catch(RuntimeException expected) { }
            } finally {data.recycle();reply.recycle();}
        } finally {context.unbindService(connection);}
        assertTrue(dead.await(5,TimeUnit.SECONDS));healthyMain();probe();
    }
}
