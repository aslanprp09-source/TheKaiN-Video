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

/** Trusted, non-dumpable isolated supervisor. Python runs only in its exec child, never ART. */
public final class IsolatedRuntimeService extends Service {
    static final String NAME="com.thekain.runtime.lab:isolated_python";
    static final String COMPONENT_PROCESS_NAME=NAME+":com.thekain.runtime.lab.IsolatedRuntimeService";
    private static final AtomicBoolean USED=new AtomicBoolean();
    private static volatile IBinder processBinder;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final HandlerThread thread=new HandlerThread("isolated-kernel-supervisor");
    private Handler monitor;
    private IIsolatedOwner owner;private IResourceOwner counters;
    private volatile RuntimeRequest request;
    private volatile int pid,protection;private int denials;
    private KernelDescriptors kernel;private long sequence;
    private volatile boolean retiring,ioDone,terminal,completed;
    private long procFailureSince=-1;
    static boolean inWorker() {
        try {
            String name=ProcessIdentity.read("/proc/self/cmdline",512).split("\u0000",2)[0];
            return (name.equals(NAME) || name.equals(COMPONENT_PROCESS_NAME)) && android.os.Process.myUid()%100000>=90000;
        }catch(IOException e) {return false;}
    }
    private static void die() {
        if(!inWorker())throw new IllegalStateException("REFUSE_OWNER_KILL");android.os.Process.killProcess(android.os.Process.myPid());
    }
    private final IIsolatedWorker.Stub binder=new IIsolatedWorker.Stub() {
        public void execute(IsolatedInput input,IIsolatedOwner callback,IResourceOwner observer) {
            if(Binder.getCallingUid()!=getApplicationInfo().uid || input==null || callback==null || observer==null) {
                if(input!=null)input.close();return;
            }
            if(!inWorker() || !USED.compareAndSet(false,true)) {input.close();die();return;}
            try {input.validate();owner=callback;counters=observer;request=input.request;
                if(request.deadline<=SystemClock.elapsedRealtime() || request.deadline-SystemClock.elapsedRealtime()>30000)throw new IOException("DEADLINE");
                owner.asBinder().linkToDeath(IsolatedRuntimeService::die,0);
                io.execute(()->run(input));
            }catch(Exception e) {input.close();die();}
        }
        public void retire(String capability) {
            if(Binder.getCallingUid()!=getApplicationInfo().uid || request==null || !request.id.equals(capability))return;
            retiring=true;monitor.post(IsolatedRuntimeService.this::killChild);
        }
    };
    private int javaDenials(String canaryPath) throws Exception {
        int flags=0;
        File canary=new File(canaryPath);
        try(InputStream ignored=new FileInputStream(canary)) {throw new IOException("OWNER_READ_ALLOWED");}
        catch(FileNotFoundException expected) {if(!deniedOrHidden(expected))throw expected;flags|=1;}
        try(OutputStream ignored=new FileOutputStream(canary,true)) {throw new IOException("OWNER_WRITE_ALLOWED");}
        catch(FileNotFoundException expected) {if(!deniedOrHidden(expected))throw expected;flags|=2;}
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
    private static boolean deniedOrHidden(Exception e) {
        // Android app-data mount isolation may hide a real owner file as ENOENT.
        // The owner proves its own unique canary exists and is unchanged before clearing the lease.
        for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof ErrnoException) {
            int errno=((ErrnoException)cause).errno;
            return errno==OsConstants.EACCES || errno==OsConstants.EPERM || errno==OsConstants.ENOENT;
        }
        return permissionDenied(e);
    }
    private static boolean permissionDenied(Exception e) {
        String m=e.getMessage();return m!=null && (m.contains("EACCES") || m.contains("EPERM") || m.contains("Permission denied") || m.contains("Operation not permitted"));
    }

    private boolean pythonLoaded() throws IOException {
        return ProcessIdentity.read("/proc/self/maps",2*1024*1024).contains("libpython3.14.so");
    }
    private void run(IsolatedInput input) {
        String stage="SUPERVISOR_IDENTITY";
        try(IsolatedInput ignored=input) {
            if(!SupervisorNative.protect() || pythonLoaded() || !counters.ready(request.id,ProcessIdentity.selfStartTicks(),true))throw new IOException("SUPERVISOR_PROTECTION");
            stage="RESOURCE_DESCRIPTOR_METADATA";
            KernelDescriptors.readOnlyRegular(input.resources,160L*1024*1024);
            KernelDescriptors.readOnlyRegular(input.guard,0);
            StructStat guardInfo=Os.fstat(input.guard.getFileDescriptor());
            if(guardInfo.st_dev!=input.guardDevice || guardInfo.st_ino!=input.guardInode || guardInfo.st_nlink!=1)throw new IOException("LEASE_GUARD_IDENTITY");
            KernelDescriptors.readOnlyRegular(input.certificate,16384);KernelDescriptors.readOnlyRegular(input.key,16384);
            if(Os.fstat(input.resources.getFileDescriptor()).st_size!=input.bytes)throw new IOException("RESOURCE_SIZE");
            stage="RESOURCE_DIGEST";
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            Os.lseek(input.resources.getFileDescriptor(),0,OsConstants.SEEK_SET);byte[] bytes=new byte[16384];int n;long total=0;
            while((n=Os.read(input.resources.getFileDescriptor(),bytes,0,bytes.length))>0) {
                total+=n;if(total>input.bytes || SystemClock.elapsedRealtime()>=request.deadline)throw new IOException("RESOURCE_LIMIT");hash.update(bytes,0,n);
            }
            if(total!=input.bytes || !IsolatedResources.hex(hash.digest()).equals(input.sha256))throw new IOException("RESOURCE_HASH");
            if(retiring)throw new IOException("RETIRED_BEFORE_EXEC");
            stage="SEALED_RESOURCES";
            try(ParcelFileDescriptor zip=ParcelFileDescriptor.adoptFd(SupervisorNative.seal(input.resources.getFd(),input.bytes));
                    ParcelFileDescriptor cert=ParcelFileDescriptor.adoptFd(SupervisorNative.seal(input.certificate.getFd(),16384));
                    ParcelFileDescriptor key=ParcelFileDescriptor.adoptFd(SupervisorNative.seal(input.key.getFd(),16384))) {
                stage="ANDROID_UID_DENIALS";denials=javaDenials(input.canaryPath);
                try {Os.write(input.resources.getFileDescriptor(),new byte[]{0},0,1);throw new IOException("WRITABLE_INPUT");}
                catch(ErrnoException expected) {if(expected.errno!=OsConstants.EBADF)throw expected;denials|=16;}
                try {Os.write(zip.getFileDescriptor(),new byte[]{0},0,1);throw new IOException("WRITABLE_SEALED_INPUT");}
                catch(ErrnoException expected) {if(expected.errno!=OsConstants.EPERM)throw expected;}
                if(retiring)throw new IOException("RETIRED_BEFORE_EXEC");
                stage="EXEC_CHILD";
                int[] spawned=SupervisorNative.spawn(getApplicationInfo().nativeLibraryDir,zip.getFd(),cert.getFd(),key.getFd(),input.guard.getFd());
                pid=spawned[0];
                try(ParcelFileDescriptor channel=ParcelFileDescriptor.adoptFd(spawned[1])) {
                    DataInputStream in=new DataInputStream(new FileInputStream(channel.getFileDescriptor()));
                    DataOutputStream out=new DataOutputStream(new FileOutputStream(channel.getFileDescriptor()));
                    stage="CHILD_EXEC_READY";
                    int hello=in.readInt();
                    if(hello==6) {int code=in.readInt();stage="CHILD_CODE_"+(code>=100 && code<=127?code:0);throw new IOException("CHILD_STARTUP");}
                    if(hello!=0 || in.readInt()!=pid || in.readInt()!=android.os.Process.myUid() || in.readInt()!=android.os.Process.myPid())
                        throw new IOException("CHILD_PROTOCOL");
                    try(ParcelFileDescriptor stat=ParcelFileDescriptor.open(new File("/proc/"+pid+"/stat"),ParcelFileDescriptor.MODE_READ_ONLY);
                            ParcelFileDescriptor status=ParcelFileDescriptor.open(new File("/proc/"+pid+"/status"),ParcelFileDescriptor.MODE_READ_ONLY)) {
                    stage="AUTHENTICATE_KERNEL";
                    if(SupervisorNative.dumpable()!=0 || pythonLoaded() || !owner.ready(request.id,UUID.randomUUID().toString(),pid))throw new IOException("OWNER_REFUSED");
                    kernel=new KernelDescriptors(pid,android.os.Process.myUid(),stat,status,true);
                    CountDownLatch first=new CountDownLatch(1);AtomicBoolean accepted=new AtomicBoolean();
                    monitor.post(()->{try {accepted.set(emit(kernel.sample(),false));}catch(Exception ignoredFirst){}finally {first.countDown();}poll();});
                    if(!first.await(1,TimeUnit.SECONDS) || !accepted.get())throw new IOException("INITIAL_COUNTER_REFUSED");
                    if(input.mode==IsolatedInput.FAKE_PROC) {
                        boolean rejected=false;
                        try(KernelDescriptors invalid=new KernelDescriptors(pid,android.os.Process.myUid(),input.resources,status,true)) { }
                        catch(IOException invalidDescriptor) {
                            if(!"READ_ONLY_REGULAR_FD_REQUIRED".equals(invalidDescriptor.getMessage())
                                    && !"NOT_KERNEL_PROC_FD".equals(invalidDescriptor.getMessage()))throw invalidDescriptor;
                            rejected=true;
                        }
                        // Publish the trusted validation failure while the child channel is
                        // still open. Unwinding that channel first can kill the waiting child
                        // and let WORKER_DIED race ahead of the actual IPC rejection.
                        completed=true;
                        owner.completed(request.id,new RuntimeResult(request.id,
                            rejected?RuntimeResult.IPC_ERROR:RuntimeResult.NATIVE_FAILED),0);
                        return; // Unexpected descriptor acceptance must fail the strict test.
                    }
                    String[] names={"","probe","analyze","block_exec","hang","crash","memory","bad_reply","output_flood","request_flood","direct_socket","byte_flood"};
                    JSONObject json=new JSONObject().put("op",input.mode==IsolatedInput.SECURITY?"isolated_security":input.mode==IsolatedInput.CHILD_FRAME?"malformed_channel":names[request.operation]);
                    if(request.url!=null)json.put("url",request.url);
                    if(input.mode==IsolatedInput.SECURITY)json.put("owner_canary",input.canaryPath);
                    if(input.mode==IsolatedInput.IPC_FLOOD && (owner.fetch(UUID.randomUUID().toString(),request.url,"GET").error!=RuntimeResult.IPC_ERROR
                            || owner.fetch(request.id,"file:///etc/passwd","GET").error!=RuntimeResult.OUT_OF_SCOPE
                            || owner.fetch(request.id,request.url,"POST").error!=RuntimeResult.INVALID_REQUEST))throw new IOException("BROKER_BOUNDARY");
                    if(input.mode==IsolatedInput.BLOCK_MAIN) {
                        CountDownLatch blocked=new CountDownLatch(1);
                        new Handler(Looper.getMainLooper()).post(()->{blocked.countDown();for(;;)try {new CountDownLatch(1).await();}catch(InterruptedException ignoredInterrupt) { }});
                        if(!blocked.await(2,TimeUnit.SECONDS))throw new IOException("MAIN_BLOCK_NOT_PROVEN");
                    }
                    byte[] encoded=json.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    out.writeInt(encoded.length);out.write(encoded);out.writeInt((int)(request.virtualHeadroom/1024/1024));
                    out.writeInt((int)Math.max(1,(request.deadline-SystemClock.elapsedRealtime()+999)/1000)+1);
                    out.writeInt(input.mode==IsolatedInput.PLATFORM_CREDENTIAL?1:0);out.writeLong(input.guardDevice);out.writeLong(input.guardInode);out.flush();stage="NATIVE_PROTOCOL";
                    int messages=0,fetches=0,bootstrapStage=0,guardProbe=0;boolean limits=false,started=false;
                    while(!retiring && messages++<32) {
                        int tag=in.readInt();
                        if(tag==9 && !started) {
                            int next=in.readInt();if(next!=bootstrapStage+1 || next>6)throw new IOException("CHILD_PROTOCOL");
                            if(next==3) {
                                int traps=in.readInt(),allowed=(1<<1)|(1<<2)|(1<<3)|(1<<5)|(1<<6)|(1<<7)|(1<<8)|(1<<10);
                                if(guardProbe!=10 || traps<0 || (traps&~allowed)!=0)throw new IOException("CHILD_PROTOCOL");
                                android.util.Log.i("TheKaiNIsolated","GUARD_KERNEL_SIGSYS_MASK="+traps);
                            }
                            bootstrapStage=next;stage="NATIVE_BOOTSTRAP_"+next;
                        }else if(tag==10 && bootstrapStage==2 && !limits && !started) {
                            int next=in.readInt();if(next!=guardProbe+1 || next>10)throw new IOException("CHILD_PROTOCOL");
                            guardProbe=next;stage="NATIVE_GUARD_PROBE_"+next;
                        }else if(tag==6) {
                            int code=in.readInt();stage="CHILD_CODE_"+(code>=100 && code<=127?code:0);throw new IOException("CHILD_STARTUP");
                        }else if(tag==3 && !limits && !started) {
                            long before=in.readLong(),address=in.readLong(),cpu=in.readLong();int mask=in.readInt();
                            if(before<=0 || address!=before+request.virtualHeadroom || cpu<=0 || cpu>64 || mask!=255)throw new IOException("CHILD_FENCE");
                            protection=mask;limits=true;if(!owner.limits(request.id,before,address,cpu,mask))throw new IOException("OWNER_GUARD_ARM");out.writeInt(0);out.flush();
                        }else if(tag==8 && limits && !started && input.mode==IsolatedInput.PLATFORM_CREDENTIAL) {
                            int proof=in.readInt();if(proof!=1 && proof!=2)throw new IOException("CHILD_PROTOCOL");
                            owner.credentialDenied(request.id,proof);completed=true;
                            owner.completed(request.id,new RuntimeResult(request.id,proof==1?RuntimeResult.INVALID_REQUEST:RuntimeResult.WORKER_DIED),0);return;
                        }else if(tag==2 && limits && !started) {
                            started=true;owner.phase(request.id);out.writeInt(0);out.flush();
                        }else if(tag==1 && started && ++fetches<=13) {
                            int method=in.readInt(),length=in.readInt();
                            if((method!=0 && method!=1) || length<1 || length>2048)throw new IOException("CHILD_REQUEST_BOUNDS");
                            byte[] address=new byte[length];in.readFully(address);
                            IsolatedReply reply=owner.fetch(request.id,new String(address,java.nio.charset.StandardCharsets.US_ASCII),method==1?"HEAD":"GET");
                            out.writeInt(reply.error);out.writeInt(reply.body==null?0:reply.body.length);if(reply.body!=null)out.write(reply.body);out.flush();
                        }else if(tag==4 && started) {
                            int length=in.readInt();if(length<1 || length>65536)throw new IOException("CHILD_OUTPUT_BOUNDS");
                            byte[] raw=new byte[length];in.readFully(raw);String text=new String(raw,java.nio.charset.StandardCharsets.UTF_8);
                            if(input.mode==IsolatedInput.SECURITY) {int mask=new JSONObject(text).optInt("security_denials");if(mask!=7)throw new IOException("PYTHON_UID_DENIALS");denials|=mask<<5;}
                            RuntimeResult result=RuntimeResult.decode(request.id,text);
                            if(request.operation==RuntimeRequest.BAD_REPLY)result=new RuntimeResult(UUID.randomUUID().toString(),RuntimeResult.OK);
                            completed=true;owner.completed(request.id,result,denials);return;
                        }else if(tag==5 && started) {completed=true;owner.completed(request.id,new RuntimeResult(request.id,RuntimeResult.OUTPUT_LIMIT),0);return;}
                        else throw new IOException("CHILD_PROTOCOL");
                    }
                    if(!retiring)throw new IOException("CHILD_MESSAGE_BUDGET");
                    }
                }
            }
        }catch(Throwable e) {
            Throwable cause=e;int errno=0;
            while(cause!=null) {if(cause instanceof ErrnoException)errno=((ErrnoException)cause).errno;cause=cause.getCause();}
            android.util.Log.e("TheKaiNIsolated","SUPERVISOR_FAILED_"+stage+"_"+e.getClass().getSimpleName()+"_errno="+errno);
            if(!retiring && !completed)try {
                int error="RESOURCE_HASH".equals(e.getMessage())?RuntimeResult.INVALID_REQUEST:
                    (e.getMessage()!=null && (e.getMessage().equals("CHILD_PROTOCOL")
                        || e.getMessage().equals("CHILD_REQUEST_BOUNDS") || e.getMessage().equals("CHILD_OUTPUT_BOUNDS")
                        || e.getMessage().equals("CHILD_MESSAGE_BUDGET")))?RuntimeResult.IPC_ERROR:
                    e instanceof EOFException?RuntimeResult.WORKER_DIED:RuntimeResult.NATIVE_FAILED;
                completed=true;owner.completed(request.id,new RuntimeResult(request.id,error),0);
            }catch(Exception ignored) { }
        }finally {
            ioDone=true;monitor.post(()->{
                if(pid==0) {try {counters.idle(request.id);}catch(Exception ignoredIdle){}}
                else {killChild();if(kernel==null)reapUnobserved();}
            });
        }
    }
    private void reapUnobserved() {
        if(SupervisorNative.reap(pid)) {try {counters.idle(request.id);}catch(Exception ignored){}return;}
        monitor.postDelayed(this::reapUnobserved,40);
    }
    private void killChild() {
        retiring=true;
        if(pid>0)try {SupervisorNative.kill(pid);}catch(Exception e) {android.util.Log.e("TheKaiNIsolated","CHILD_KILL_REFUSED");}
    }
    private boolean emit(KernelDescriptors.Sample sample,boolean dead) throws Exception {
        KernelDescriptors.Sample self=KernelDescriptors.selfSample();
        return counters.sample(request.id,++sequence,SystemClock.elapsedRealtime(),pid,android.os.Process.myUid(),kernel.start,
            sample==null?0:sample.cpu,sample==null?0:sample.rss,sample==null?0:sample.vm,dead,self.rss,self.cpu,pythonLoaded(),protection,dead?SupervisorNative.exitSignal(pid):-1,dead?SupervisorNative.exitCode(pid):-1);
    }
    private void poll() {
        if(terminal || kernel==null)return;
        try {
            KernelDescriptors.Sample sample=kernel.sample();
            if(sample.zombie) {SupervisorNative.reap(pid);terminal=true;emit(null,true);logExit();return;}
            procFailureSince=-1;
            if(!emit(sample,false)) {killChild();return;}
            if(sample.rss>request.rssLimit || SystemClock.elapsedRealtime()>=request.deadline)killChild();
        }catch(Exception e) {
            if(kernel.dead()) {SupervisorNative.reap(pid);try {terminal=true;emit(null,true);logExit();}catch(Exception ignored){}return;}
            if(procFailureSince<0)procFailureSince=SystemClock.elapsedRealtime();
            try {counters.failed(request.id);}catch(Exception ignored) { }
            if(SystemClock.elapsedRealtime()-procFailureSince>=120)killChild();
        }
        monitor.postDelayed(this::poll,40);
    }
    private void logExit() {
        android.util.Log.i("TheKaiNIsolated","CHILD_EXIT_signal="+SupervisorNative.exitSignal(pid)+"_code="+SupervisorNative.exitCode(pid));
    }
    @Override public void onCreate() {super.onCreate();thread.start();monitor=new Handler(thread.getLooper());}
    @Override public IBinder onBind(Intent intent) {if(processBinder!=null && processBinder!=binder) {die();return null;}processBinder=binder;return binder;}
    @Override public boolean onUnbind(Intent intent) {return false;}
}
