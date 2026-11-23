package com.thekain.runtime.lab;

import android.content.*;
import android.os.*;
import android.system.Os;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Additional contract tests, after the unchanged observer-loss refusal test. */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class LeaseRecoveryTest extends RuntimeTestSupport {
    private IsolatedRuntime.Outcome fresh(IsolatedRuntime runtime) throws Exception {
        IsolatedRuntime.Outcome out=runtime.submit(RuntimeRequest.ANALYZE,url,1,30000).completion().get(35,TimeUnit.SECONDS);
        assertEquals(0,out.result.error);assertEquals(1,out.result.pythonCalls);assertTrue(out.deathConfirmed);
        assertTrue(out.ownerCanaryIntact);assertNotEquals(android.os.Process.myUid(),out.workerUid);healthyMain();return out;
    }
    @Test public void aPriorObserverLossNeedsExplicitKernelProofBeforeFreshAnalysis() throws Exception {
        IsolatedLease lease=new IsolatedLease(context);assertTrue("Prior refusal test must retain its real uncertainty",lease.uncertain());
        try(IsolatedRuntime runtime=new IsolatedRuntime(context)) {
            assertEquals(RuntimeResult.CLEANUP_FAILED,runtime.submit(RuntimeRequest.PROBE,null,1,30000).completion().get(2,TimeUnit.SECONDS).result.error);
            IsolatedRuntime.RecoveryOutcome proof=runtime.recoverInterruptedWorker().get(4,TimeUnit.SECONDS);
            assertEquals(IsolatedLease.RECOVERED,proof.status);assertTrue(proof.inode>0);assertFalse(lease.uncertain());fresh(runtime);
        }
    }
    @Test public void bLiveGuardAndUnverifiedFixturesCannotClearUncertainty() throws Exception {
        try(IsolatedRuntime runtime=new IsolatedRuntime(context)) {
            IsolatedRuntime.Operation op=runtime.submit(RuntimeRequest.HANG,null,1,30000);op.whenPythonStarted().get(25,TimeUnit.SECONDS);
            assertEquals(-1,LeaseNative.acquire(new File(context.getFilesDir(),"isolated-lease-"+op.id+".lock").getPath(),op.guard.device,op.guard.inode));
            try(IsolatedRuntime other=new IsolatedRuntime(context)) {
                assertEquals(IsolatedLease.BUSY,other.recoverInterruptedWorker().get(4,TimeUnit.SECONDS).status);
            }
            assertFalse(op.completion().isDone());assertTrue(op.cancel());
            IsolatedRuntime.Outcome canceled=op.completion().get(5,TimeUnit.SECONDS);assertTrue(canceled.deathConfirmed);fresh(runtime);
        }
        // Never-bound private fixtures only: no real worker's lease is erased or reset.
        for(String fault:new String[]{"unarmed","missing","inode","canary","legacy","corrupt"}) {
            File folder=new File(context.getCacheDir(),"lease-fixture-"+UUID.randomUUID());assertTrue(folder.mkdir());
            Context fixture=new ContextWrapper(context) {@Override public File getFilesDir() {return folder;}};
            IsolatedLease lease=new IsolatedLease(fixture);String id=UUID.randomUUID().toString();IsolatedLease.Guard guard=null;
            try {
                if(fault.equals("legacy"))lease.reserve(id);
                else if(fault.equals("corrupt")) {try(FileOutputStream out=new FileOutputStream(lease.file.getBaseFile())) {out.write('{');}}
                else {
                    guard=lease.createGuard(id);lease.reserve(id,guard);
                    if(!fault.equals("unarmed"))assertTrue(lease.arm(id));
                    try(FileOutputStream out=new FileOutputStream(new File(folder,"isolated-owner-canary"))) {
                        out.write((fault.equals("canary")?"changed":"owner-only-"+id).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    }
                    guard.close();File path=new File(folder,"isolated-lease-"+id+".lock");
                    if(fault.equals("missing"))assertTrue(path.delete());
                    if(fault.equals("inode")) {assertTrue(path.renameTo(new File(folder,"original.lock")));assertTrue(path.createNewFile());Os.chmod(path.getPath(),0600);}
                }
                if(!fault.equals("corrupt"))lease.poison();
                assertEquals(fault,IsolatedLease.UNVERIFIED,lease.recover().status);assertTrue(lease.uncertain());
                assertTrue(lease.file.getBaseFile().exists());
            }finally {if(guard!=null)guard.close();for(File file:folder.listFiles())assertTrue(file.delete());assertTrue(folder.delete());}
        }
        // Model the durable REAPED crash window, after obtaining real exclusive proof.
        File committedFolder=new File(context.getCacheDir(),"lease-fixture-"+UUID.randomUUID());assertTrue(committedFolder.mkdir());
        Context committedContext=new ContextWrapper(context) {@Override public File getFilesDir() {return committedFolder;}};
        IsolatedLease committed=new IsolatedLease(committedContext);String committedId=UUID.randomUUID().toString();
        IsolatedLease.Guard committedGuard=committed.createGuard(committedId);
        try {
            committed.reserve(committedId,committedGuard);assertTrue(committed.arm(committedId));committed.poison();
            try(FileOutputStream out=new FileOutputStream(new File(committedFolder,"isolated-owner-canary"))) {
                out.write(("owner-only-"+committedId).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            committedGuard.close();int proof=LeaseNative.acquire(new File(committedFolder,"isolated-lease-"+committedId+".lock").getPath(),committedGuard.device,committedGuard.inode);
            assertTrue(proof>=0);
            try(ParcelFileDescriptor exclusive=ParcelFileDescriptor.adoptFd(proof)) {
                JSONObject durable=new JSONObject(ProcessIdentity.read(committed.file.getBaseFile().getPath(),4096)).put("phase","REAPED");
                FileOutputStream out=committed.file.startWrite();out.write(durable.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));committed.file.finishWrite(out);
                assertTrue(new File(committedFolder,"isolated-lease-"+committedId+".lock").delete());
            }
            assertEquals(IsolatedLease.RECOVERED,committed.recover().status);assertFalse(committed.file.getBaseFile().exists());
        }finally {committedGuard.close();for(File file:committedFolder.listFiles())assertTrue(file.delete());assertTrue(committedFolder.delete());}
    }
    @Test public void cRepeatedSupervisorLossAllowsFreshProcessOnlyAfterExplicitRecovery() throws Exception {
        try(IsolatedRuntime runtime=new IsolatedRuntime(context)) {
            for(int i=0;i<2;i++) {
                IsolatedRuntime.Operation op=runtime.submit(RuntimeRequest.HANG,null,1,30000);op.whenPythonStarted().get(25,TimeUnit.SECONDS);
                Context binding=context.getApplicationContext();binding.unbindService(op.connection);op.bound=false;
                binding.stopService(new Intent(binding,IsolatedRuntimeService.class));
                IsolatedRuntime.Outcome unknown=op.completion().get(6,TimeUnit.SECONDS);
                assertEquals(RuntimeResult.CLEANUP_FAILED,unknown.result.error);assertFalse(unknown.deathConfirmed);
                assertEquals(RuntimeResult.CLEANUP_FAILED,runtime.submit(RuntimeRequest.PROBE,null,1,30000).completion().get(2,TimeUnit.SECONDS).result.error);
                IsolatedRuntime.RecoveryOutcome proof=runtime.recoverInterruptedWorker().get(4,TimeUnit.SECONDS);
                assertEquals(IsolatedLease.RECOVERED,proof.status);assertEquals(op.id,proof.operationId);
                assertFalse("Recovery must not rewrite the old missing-proc result",unknown.deathConfirmed);
                IsolatedRuntime.Outcome next=fresh(runtime);assertNotEquals(op.instance,next.instance);
            }
        }
    }
}
