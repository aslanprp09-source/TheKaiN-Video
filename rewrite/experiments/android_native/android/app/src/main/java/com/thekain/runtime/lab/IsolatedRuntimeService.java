package com.thekain.runtime.lab;

import android.app.Service;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.system.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Genuine Android isolated UID: not a process holding the application's permissions. */
public final class IsolatedRuntimeService extends Service {
    static final String NAME="com.thekain.runtime.lab:isolated_python";
    static final String COMPONENT_PROCESS_NAME=NAME+":com.thekain.runtime.lab.IsolatedRuntimeService";
    private static final AtomicBoolean USED=new AtomicBoolean();
    // Keep the endpoint strongly rooted until PID death, including pre-Python handshake refusal.
    // Binder object GC must never masquerade as worker process death.
    private static volatile IBinder processBinder;
    private IIsolatedOwner ownerLease;
    private final ExecutorService python=Executors.newSingleThreadExecutor(r->new Thread(r,"isolated-python"));
    static boolean inWorker() {
        try {
            String name=ProcessIdentity.read("/proc/self/cmdline",512).split("\u0000",2)[0];
            // Android decorates isolated Service process names with the exact component.
            return (name.equals(NAME) || name.equals(COMPONENT_PROCESS_NAME)) && android.os.Process.myUid()%100000>=90000;
        }
        catch(IOException e) {return false;}
    }
    private static void die() {
        if(!inWorker())throw new IllegalStateException("REFUSE_OWNER_KILL");
        android.os.Process.killProcess(android.os.Process.myPid());
    }
    private final IIsolatedWorker.Stub binder=new IIsolatedWorker.Stub() {
        @Override public void execute(IsolatedInput input,IIsolatedOwner owner) {
            // The Android binding is private; only the installed package UID may submit work.
            if(Binder.getCallingUid()!=getApplicationInfo().uid || input==null || owner==null) {
                android.util.Log.e("TheKaiNIsolated","CALLER_REJECTED_"+Binder.getCallingUid()+"_"+getApplicationInfo().uid);
                if(input!=null)input.close();return;
            }
            if(!inWorker() || !USED.compareAndSet(false,true)) {input.close();die();return;}
            String stage="VALIDATE";ownerLease=owner;
            try {
                input.validate();long left=input.request.deadline-SystemClock.elapsedRealtime();
                if(left<=0 || left>NativeRuntime.MAX_DEADLINE_MS)throw new IOException("DEADLINE");
                stage="OWNER_DEATH_LINK";owner.asBinder().linkToDeath(IsolatedRuntimeService::die,0);
                stage="OPEN_PROC_DESCRIPTORS";
                try(ParcelFileDescriptor stat=ParcelFileDescriptor.open(new File("/proc/self/stat"),ParcelFileDescriptor.MODE_READ_ONLY);
                        ParcelFileDescriptor status=ParcelFileDescriptor.open(new File("/proc/self/status"),ParcelFileDescriptor.MODE_READ_ONLY)) {
                    if(input.mode==IsolatedInput.FAKE_PROC) {
                        if(owner.ready(input.request.id,UUID.randomUUID().toString(),input.resources,status))throw new IOException("FAKE_FD_ACCEPTED");
                        throw new IOException("EXPECTED_FAKE_FD_REFUSAL");
                    }
                    stage="READY_TRANSACTION";
                    if(!owner.ready(input.request.id,UUID.randomUUID().toString(),stat,status))throw new IOException("PEER_REFUSED");
                }
                python.execute(()->run(input,owner));
            }catch(Exception e) {
                android.util.Log.e("TheKaiNIsolated","HANDSHAKE_REFUSED_"+stage+"_"+e.getClass().getSimpleName()+"_alive="+owner.asBinder().isBinderAlive());
                for(StackTraceElement frame:e.getStackTrace())
                    android.util.Log.e("TheKaiNIsolated","HANDSHAKE_FRAME_"+frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber());
                input.close();try {owner.completed(input.request.id,new RuntimeResult(input.request.id,RuntimeResult.IPC_ERROR),0);}catch(Exception ignored){}
            }
        }
    };
    private int javaDenials() throws Exception {
        int flags=0;
        File canary=new File(getApplicationInfo().dataDir,"files/isolated-owner-canary");
        try(InputStream ignored=new FileInputStream(canary)) {throw new IOException("OWNER_READ_ALLOWED");}
        catch(FileNotFoundException expected) {if(!permissionDenied(expected))throw expected;flags|=1;}
        try(OutputStream ignored=new FileOutputStream(canary,true)) {throw new IOException("OWNER_WRITE_ALLOWED");}
        catch(FileNotFoundException expected) {if(!permissionDenied(expected))throw expected;flags|=2;}
        // Before the additional Python seccomp fence: prove the Android UID itself lacks network.
        try(java.net.Socket socket=new java.net.Socket()) {socket.connect(new InetSocketAddress("127.0.0.1",9),500);
            throw new IOException("ISOLATED_SOCKET_ALLOWED");}
        catch(SecurityException denied) {
            if(checkSelfPermission(android.Manifest.permission.INTERNET)!=PackageManager.PERMISSION_DENIED)throw denied;flags|=4;
        }catch(SocketException denied) {
            Throwable cause=denied;boolean permission=false;
            while(cause!=null) {if(cause instanceof ErrnoException) {int n=((ErrnoException)cause).errno;permission|=n==OsConstants.EACCES || n==OsConstants.EPERM;}cause=cause.getCause();}
            if(!permission && !permissionDenied(denied))throw denied;flags|=4;
        }
        if(checkSelfPermission(android.Manifest.permission.INTERNET)!=PackageManager.PERMISSION_DENIED)
            throw new IOException("ISOLATED_INTERNET_PERMISSION");
        return flags|8;
    }
    private static boolean permissionDenied(Exception e) {
        String m=e.getMessage();return m!=null && (m.contains("EACCES") || m.contains("EPERM") || m.contains("Permission denied") || m.contains("Operation not permitted"));
    }
    private void run(IsolatedInput input,IIsolatedOwner owner) {
        String stage="READ_ONLY_CAPABILITIES";
        try(IsolatedInput ignored=input) {
            KernelDescriptors.readOnlyRegular(input.resources,160L*1024*1024);
            KernelDescriptors.readOnlyRegular(input.certificate,16384);KernelDescriptors.readOnlyRegular(input.key,16384);
            if(Os.fstat(input.resources.getFileDescriptor()).st_size!=input.bytes)throw new IOException("RESOURCE_SIZE");
            stage="RESOURCE_DIGEST";
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            Os.lseek(input.resources.getFileDescriptor(),0,OsConstants.SEEK_SET);byte[] bytes=new byte[16384];int n;long total=0;
            while((n=Os.read(input.resources.getFileDescriptor(),bytes,0,bytes.length))>0) {
                total+=n;if(total>input.bytes || SystemClock.elapsedRealtime()>=input.request.deadline)throw new IOException("RESOURCE_LIMIT");hash.update(bytes,0,n);
            }
            if(total!=input.bytes || !IsolatedResources.hex(hash.digest()).equals(input.sha256))throw new IOException("RESOURCE_HASH");
            stage="SEALED_RESOURCE_COPY";
            try(ParcelFileDescriptor sealed=ParcelFileDescriptor.adoptFd(IsolatedChild.sealedCopy(input.resources.getFd(),input.bytes));
                    ParcelFileDescriptor cert=ParcelFileDescriptor.adoptFd(IsolatedChild.sealedCopy(input.certificate.getFd(),16384));
                    ParcelFileDescriptor key=ParcelFileDescriptor.adoptFd(IsolatedChild.sealedCopy(input.key.getFd(),16384))) {
            stage="ANDROID_UID_DENIALS";
            int denials=javaDenials();
            try {Os.write(input.resources.getFileDescriptor(),new byte[]{0},0,1);throw new IOException("WRITABLE_RESOURCES");}
            catch(ErrnoException expected) {if(expected.errno!=OsConstants.EBADF)throw expected;denials|=16;}
            try {Os.write(sealed.getFileDescriptor(),new byte[]{0},0,1);throw new IOException("UNSEALED_RESOURCES");}
            catch(ErrnoException expected) {if(expected.errno!=OsConstants.EPERM)throw expected;}
            RuntimeRequest request=input.request;
            if(input.mode==IsolatedInput.IPC_FLOOD) {
                // Wrong capability and forbidden destination must not consume the owner's budget.
                if(owner.fetch(UUID.randomUUID().toString(),request.url,"GET").error!=RuntimeResult.IPC_ERROR
                        || owner.fetch(request.id,"file:///etc/passwd","GET").error!=RuntimeResult.OUT_OF_SCOPE
                        || owner.fetch(request.id,request.url,"POST").error!=RuntimeResult.INVALID_REQUEST)
                    throw new IOException("BROKER_ACCEPTED_FORBIDDEN_REQUEST");
            }
            if(input.mode==IsolatedInput.BLOCK_MAIN) {
                CountDownLatch entered=new CountDownLatch(1);
                new Handler(Looper.getMainLooper()).post(()->{
                    entered.countDown();CountDownLatch never=new CountDownLatch(1);
                    for(;;)try {never.await();}catch(InterruptedException ignoredInterrupt) { }
                });
                if(!entered.await(2,TimeUnit.SECONDS))throw new IOException("MAIN_THREAD_BLOCK_NOT_CONFIRMED");
            }
            long left=request.deadline-SystemClock.elapsedRealtime();
            int seconds=(int)Math.max(1,(left+999)/1000)+1;
            stage="KERNEL_LIMITS";
            long[] limits=IsolatedChild.configureBudget(request.virtualHeadroom,seconds);
            if(limits.length!=4 || limits[3]!=1)throw new IOException("KERNEL_FENCE");
            owner.limits(request.id,limits[0],limits[1],limits[2]);
            String[] names={"","probe","analyze","block_exec","hang","crash","memory","bad_reply","output_flood","request_flood","direct_socket","byte_flood"};
            JSONObject json=new JSONObject().put("op",input.mode==IsolatedInput.SECURITY?"isolated_security":names[request.operation]);
            if(request.url!=null)json.put("url",request.url);
            if(input.mode==IsolatedInput.SECURITY)json.put("owner_data",getApplicationInfo().dataDir);
            String resources="fd:"+sealed.getFd()+":"+cert.getFd()+":"+key.getFd();
            stage="PYTHON";
            String raw=IsolatedChild.call(resources,getApplicationInfo().nativeLibraryDir,json.toString(),new IsolatedGate(owner,request.id));
            if(input.mode==IsolatedInput.SECURITY) {
                int mask=new JSONObject(raw).optInt("security_denials");if(mask!=7)throw new IOException("PYTHON_SANDBOX_PROBE");denials|=mask<<5;
            }
            RuntimeResult result=RuntimeResult.decode(request.id,raw);
            if(request.operation==RuntimeRequest.BAD_REPLY)result=new RuntimeResult(UUID.randomUUID().toString(),RuntimeResult.OK);
            owner.completed(request.id,result,denials);
            }
        }catch(Throwable e) {
            android.util.Log.e("TheKaiNIsolated","WORKER_FAILED_"+stage+"_"+e.getClass().getSimpleName());
            try {owner.completed(input.request.id,new RuntimeResult(input.request.id,
                "RESOURCE_HASH".equals(e.getMessage())?RuntimeResult.INVALID_REQUEST:
                e instanceof OutOfMemoryError?RuntimeResult.MEMORY_LIMIT:
                "OUTPUT_LIMIT".equals(e.getMessage())?RuntimeResult.OUTPUT_LIMIT:RuntimeResult.NATIVE_FAILED),0);}catch(Exception ignored) { }
        }
        // No cooperative self-kill/onDestroy path: unbind must let Android reap even a blocked main thread.
    }
    @Override public IBinder onBind(Intent intent) {
        if(processBinder!=null && processBinder!=binder) {die();return null;}
        processBinder=binder;return binder;
    }
    @Override public boolean onUnbind(Intent intent) {return false;}
}
