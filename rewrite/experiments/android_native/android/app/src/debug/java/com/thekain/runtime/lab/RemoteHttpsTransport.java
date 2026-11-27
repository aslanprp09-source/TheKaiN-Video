package com.thekain.runtime.lab;

import android.app.ActivityManager;
import android.content.*;
import android.os.*;
import com.thekain.runtime.lab.network.PublicHttpsPolicy;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Owner-side bounded IPC client. It never performs DNS/TLS/HTTP itself. */
public final class RemoteHttpsTransport implements IsolatedTransport {
    private static final AtomicReference<RemoteHttpsTransport> LIVE=new AtomicReference<>();
    public static final class Policy implements IsolatedTransport.Factory {
        final Set<String> origins;final String fixture;final byte[] ca;final int fault;final long rss,cpu;
        private final Runnable readyReturnBarrier;
        private volatile RemoteHttpsTransport latest;
        private Policy(Set<String> origins,String fixture,byte[] ca,int fault,long rss,long cpu) throws Exception {
            this(origins,fixture,ca,fault,rss,cpu,null);
        }
        private Policy(Set<String> origins,String fixture,byte[] ca,int fault,long rss,long cpu,Runnable barrier) throws Exception {
            if(fixture==null)new PublicHttpsPolicy(origins);
            else {
                java.net.URI uri=new java.net.URI(fixture);
                if(!"https".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort()<=0
                    || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null || uri.getRawQuery()!=null || !uri.getRawPath().isEmpty())throw new IOException("OWNED_FIXTURE_SCOPE");
            }
            this.origins=Collections.unmodifiableSet(new HashSet<>(origins));this.fixture=fixture;this.ca=ca==null?null:ca.clone();
            this.fault=fault;this.rss=rss;this.cpu=cpu;
            readyReturnBarrier=barrier;
        }
        public static Policy publicOrigins(Set<String> origins) throws Exception {return new Policy(origins,null,null,0,RuntimeRequest.MAX_RSS,10000);}
        static Policy fixture(String origin,byte[] ca) throws Exception {return new Policy(Collections.singleton(origin),origin,ca,0,RuntimeRequest.MAX_RSS,10000);}
        Policy fault(int value,long rss,long cpu) throws Exception {return new Policy(origins,fixture,ca,value,rss,cpu);}
        // Trusted instrumentation only, owned loopback fixture only. Nothing executable crosses IPC.
        Policy holdReadyReturnForTests(Runnable barrier) throws Exception {
            if(fixture==null || barrier==null)throw new IllegalArgumentException("OWNED_FIXTURE_ONLY");
            return new Policy(origins,fixture,ca,fault,rss,cpu,barrier);
        }
        public IsolatedTransport create(Context context,RuntimeRequest request,Counters counters) throws Exception {
            RemoteHttpsTransport value=new RemoteHttpsTransport(context,request,this,counters,false);latest=value;return value;
        }
        public JSONObject evidence() throws Exception {RemoteHttpsTransport value=latest;return value==null?new JSONObject().put("not_started",true):value.evidence();}
        RemoteHttpsTransport latest() {return latest;}
        public void validateCandidate(String url) throws Exception {
            if(fixture==null)new PublicHttpsPolicy(origins).target(url);
            else {RuntimeRequest.validateUrl(url);if(new java.net.URI(url).getPort()!=new java.net.URI(fixture).getPort())throw new IOException("OUT_OF_SCOPE");}
        }
        @Override public void validate(AnalysisMetadata analysis) throws Exception {for(AnalysisMetadata.Format f:analysis.formats())validateCandidate(f.url);}
    }
    private final Context context;
    private final RuntimeRequest request;
    private final boolean transfer;
    private volatile DownloadSession download;
    private final Counters counters;
    private final IsolatedLease lease;
    private final IsolatedLease.Guard guard;
    private final HandlerThread thread=new HandlerThread("remote-https-lifecycle");
    private final Handler lifecycle;
    private final ExecutorService connectionIo=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1));
    private final CompletableFuture<Boolean> ready=new CompletableFuture<>(),lookupStarted=new CompletableFuture<>(),cpuStarted=new CompletableFuture<>();
    private final AtomicBoolean fetching=new AtomicBoolean();
    private volatile BrokerHello hello;
    private volatile IBinder binder;
    private volatile IRemoteHttps remote;
    private volatile boolean closed,done,bound,binderDead,connected,connectionWorkDone=true,configured,guardClosed,leaseClean,kernelGone,serviceGone;
    private volatile int fatal,requests;
    private volatile String handshake="BIND_PENDING";
    private volatile boolean readyNotified,configureReturned;
    private volatile String configureFailure="NONE";
    private volatile long payload,peakRss,peakCpuMs,initialCpu,closeAt=-1,finishedAt;
    private final ServiceConnection connection;
    private RemoteHttpsTransport(Context context,RuntimeRequest request,Policy policy,Counters counters,boolean transfer) throws Exception {
        this.context=context.getApplicationContext();this.request=request;this.counters=counters;this.transfer=transfer;lease=new IsolatedLease(this.context,"https");
        if(!ProcessIdentity.read("/proc/self/cmdline",512).split("\u0000",2)[0].equals(context.getPackageName()))throw new IOException("BROKER_OWNER_REQUIRED");
        RemoteHttpsTransport previous=LIVE.get();if(previous!=null && previous.terminated())LIVE.compareAndSet(previous,null);
        if(!LIVE.compareAndSet(null,this))throw new IOException("BROKER_BUSY");
        IsolatedLease.Guard prepared=null;
        try {
            if(lease.pending() || lease.uncertain())throw new IOException("BROKER_RECOVERY_REQUIRED");
            try(FileOutputStream out=context.openFileOutput("https-owner-canary",Context.MODE_PRIVATE)) {out.write(("owner-only-"+request.id).getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
            prepared=lease.createGuard(request.id);lease.reserve(request.id,prepared);guard=prepared;
        }catch(Exception e) {if(prepared!=null)lease.finishKnownDead(request.id,prepared);LIVE.compareAndSet(this,null);throw e;}
        thread.start();lifecycle=new Handler(thread.getLooper());
        connection=new ServiceConnection() {
            public void onServiceConnected(ComponentName name,IBinder value) {
                connected=true;binder=value;remote=IRemoteHttps.Stub.asInterface(value);connectionWorkDone=false;
                try {value.linkToDeath(()->{binderDead=true;fail(RuntimeResult.WORKER_DIED);},0);
                    connectionIo.execute(()->connect(policy));
                }catch(Exception e) {connectionWorkDone=true;fail(RuntimeResult.IPC_ERROR);}
            }
            public void onServiceDisconnected(ComponentName name) {binderDead=true;fail(RuntimeResult.WORKER_DIED);}
            public void onBindingDied(ComponentName name) {onServiceDisconnected(name);}
            public void onNullBinding(ComponentName name) {fail(RuntimeResult.IPC_ERROR);}
        };
        try {bound=context.bindService(new Intent(context,RemoteHttpsService.class),connection,Context.BIND_AUTO_CREATE);if(!bound)fail(RuntimeResult.IPC_ERROR);}
        catch(RuntimeException unavailable) {fail(RuntimeResult.IPC_ERROR);}
        lifecycle.post(()->watch(policy));
    }
    private void connect(Policy policy) {
        try {
            if(closed)return;handshake="HELLO";BrokerHello value=remote.hello();BrokerProcess actual=BrokerProcess.authenticate(context,value);
            hello=value;initialCpu=actual.cpu;peakRss=actual.rss;
            if(closed)return;
            if(actual.rss>policy.rss) {fail(RuntimeResult.MEMORY_LIMIT);return;}
            BrokerConfig config=new BrokerConfig(request.id,request.deadline,policy.origins.toArray(new String[0]),policy.fixture,policy.ca,policy.fault,policy.rss,policy.cpu,guard,transfer);
            configured=true;handshake="CONFIGURE";
            remote.configure(config,new IRemoteHttpsOwner.Stub() {
                private boolean sender(String cap) {return request.id.equals(cap) && hello!=null && Binder.getCallingPid()==hello.pid && Binder.getCallingUid()==android.os.Process.myUid();}
                public boolean ready(String cap,int pid,long start) {
                    if(!sender(cap) || closed || pid!=hello.pid || start!=hello.start)return false;
                    return lease.arm(request.id);
                }
                public void phase(String cap,int phase) {
                    if(!sender(cap) || closed)return;
                    if(phase==0) {
                        readyNotified=true;handshake="CONFIGURE_ACK";
                        if(policy.readyReturnBarrier!=null)policy.readyReturnBarrier.run();
                    }else if(phase==1)lookupStarted.complete(true);else if(phase==2)cpuStarted.complete(true);
                }
            });
            configureReturned=true;
            if(!readyNotified)throw new IOException("BROKER_READY_MISSING");
            // The callback runs inside the configure transaction. Publishing readiness there
            // lets a concurrent fetch kill the worker while configure is still in flight.
            if(!closed) {handshake="READY";ready.complete(true);}
        }catch(Exception invalid) {
            configureFailure=invalid instanceof DeadObjectException?"DEAD_OBJECT":invalid instanceof RemoteException?"REMOTE":"PROTOCOL";
            fail(invalid instanceof DeadObjectException?RuntimeResult.WORKER_DIED:RuntimeResult.IPC_ERROR);
        }
        finally {connectionWorkDone=true;if(closed)lifecycle.post(this::reap);}
    }
    private int remaining() throws ChildNetworkGate.Failure {
        if(closed)throw new ChildNetworkGate.Failure(fatal==0?RuntimeResult.CANCELED:fatal);
        long left=request.deadline-SystemClock.elapsedRealtime();
        if(left<=0) {fail(RuntimeResult.TIMEOUT);throw new ChildNetworkGate.Failure(RuntimeResult.TIMEOUT);}return (int)left;
    }
    public IsolatedReply reply(String url,String method) throws ChildNetworkGate.Failure {
        if(transfer)throw new ChildNetworkGate.Failure(RuntimeResult.OUT_OF_SCOPE);
        if(!fetching.compareAndSet(false,true))throw new ChildNetworkGate.Failure(RuntimeResult.BUSY);
        try {
            remaining();ready.get(remaining(),TimeUnit.MILLISECONDS);remaining();
            int seq=++requests;
            BrokerChunk first=remote.fetch(request.id,seq,url,method);remaining();first.validate();
            if(first.sequence!=seq || first.offset!=0)throw new IOException("BROKER_SEQUENCE");
            counters.update(first.requests,first.payload);payload=first.payload;
            if(first.error!=0)throw new ChildNetworkGate.Failure(first.error);
            byte[] body=new byte[first.total];int offset=0;BrokerChunk chunk=first;
            for(;;) {
                if(chunk.error!=0 || chunk.sequence!=seq || chunk.offset!=offset || chunk.total!=first.total || chunk.status!=first.status
                    || !Objects.equals(chunk.finalUrl,first.finalUrl) || !Objects.equals(chunk.contentType,first.contentType)
                    || chunk.declaredBytes!=first.declaredBytes || chunk.requests!=first.requests || chunk.payload!=first.payload)
                    throw new IOException("BROKER_CHUNK_IDENTITY");
                System.arraycopy(chunk.data,0,body,offset,chunk.data.length);offset+=chunk.data.length;
                if(offset==body.length)break;remaining();chunk=remote.read(request.id,seq,offset);remaining();chunk.validate();
            }
            synchronized(this) {remaining();return new IsolatedReply(0,body,first.status,first.finalUrl,first.contentType,first.declaredBytes);}
        }catch(ChildNetworkGate.Failure failed) {
            if(failed.code==RuntimeResult.TIMEOUT || failed.code==RuntimeResult.WORKER_DIED)fail(failed.code);
            throw failed;
        }
        catch(TimeoutException e) {fail(RuntimeResult.TIMEOUT);throw new ChildNetworkGate.Failure(RuntimeResult.TIMEOUT);}
        catch(InterruptedException e) {Thread.currentThread().interrupt();fail(RuntimeResult.CANCELED);throw new ChildNetworkGate.Failure(RuntimeResult.CANCELED);}
        catch(Exception failed) {int code=fatal!=0?fatal:closed?RuntimeResult.CANCELED:failed instanceof DeadObjectException?RuntimeResult.WORKER_DIED:RuntimeResult.IPC_ERROR;fail(code);throw new ChildNetworkGate.Failure(code);}
        finally {fetching.set(false);if(closed)lifecycle.post(this::reap);}
    }
    /** Trusted app transfer only; no Python operation is submitted or bound. Same admission/kill/lease proof. */
    public static RemoteHttpsTransport forDownload(Context context,Policy policy,String url,long timeoutMs) throws Exception {
        if(timeoutMs<100 || timeoutMs>30000)throw new IllegalArgumentException("TRANSFER_DEADLINE");
        return forDownloadUntil(context,policy,url,SystemClock.elapsedRealtime()+timeoutMs);
    }
    public static RemoteHttpsTransport forDownloadUntil(Context context,Policy policy,String url,long deadline) throws Exception {
        long left=deadline-SystemClock.elapsedRealtime();
        if(left<100 || left>30000)throw new IllegalArgumentException("TRANSFER_DEADLINE");
        policy.validateCandidate(url);
        int recovered=recover(context);if(recovered!=IsolatedLease.NO_PENDING && recovered!=IsolatedLease.RECOVERED)throw new IOException("BROKER_RECOVERY_REQUIRED");
        RuntimeRequest request=new RuntimeRequest(UUID.randomUUID().toString(),RuntimeRequest.SOURCE_ANALYZE,url,RuntimeRequest.CA_OWN,
            deadline,RuntimeRequest.MAX_RSS,RuntimeRequest.MAX_HEADROOM);
        return new RemoteHttpsTransport(context,request,policy,(count,bytes)->{},true);
    }
    public void expireDownload() {
        if(!transfer)throw new IllegalStateException("TRANSFER_REQUIRED");
        fail(RuntimeResult.TIMEOUT);
    }
    public DownloadSession openDownload() throws Exception {
        if(!transfer || !fetching.compareAndSet(false,true))throw new IOException("TRANSFER_ADMISSION");
        ParcelFileDescriptor[] pipe=null;
        try {
            remaining();ready.get(remaining(),TimeUnit.MILLISECONDS);remaining();
            pipe=ParcelFileDescriptor.createPipe();
            DownloadSession session=new DownloadSession(pipe[0]);download=session;
            remaining();
            final ParcelFileDescriptor writer=pipe[1];requests=1;
            connectionIo.execute(()->{
                try {
                    remaining();BrokerDownload value=remote.download(request.id,request.url,writer);value.validate();
                    session.result.complete(value);
                }catch(Exception failed) {session.result.completeExceptionally(new IOException("TRANSFER_IPC"));}
                finally {try {writer.close();}catch(IOException ignored){}if(closed)lifecycle.post(this::reap);}
            });
            return session;
        }catch(Exception failed) {
            if(pipe!=null)for(ParcelFileDescriptor fd:pipe)try {fd.close();}catch(IOException ignored){}
            fetching.set(false);fail(failed instanceof TimeoutException?RuntimeResult.TIMEOUT:RuntimeResult.IPC_ERROR);throw failed;
        }
    }
    public final class DownloadSession implements AutoCloseable {
        private final CompletableFuture<BrokerDownload> result=new CompletableFuture<>();
        private final InputStream input;
        private boolean sessionClosed,reading;
        private long received;
        private boolean eof;
        private DownloadSession(ParcelFileDescriptor reader) {input=new ParcelFileDescriptor.AutoCloseInputStream(reader);}
        /** Only a blocking primitive: never writes a file or invokes application callbacks. */
        public int read(byte[] bytes) throws Exception {
            synchronized(this) {if(sessionClosed || reading)throw new IOException("TRANSFER_CLOSED");reading=true;}
            try {
                remaining();int count=input.read(bytes);remaining();
                if(count<0)eof=true;else {received+=count;if(received>BrokerDownload.MAX_BYTES)throw new IOException("TRANSFER_LIMIT");}
                return count;
            }finally {synchronized(this) {reading=false;if(sessionClosed)fetching.set(false);}if(closed)lifecycle.post(RemoteHttpsTransport.this::reap);}
        }
        public BrokerDownload receipt() throws Exception {
            remaining();BrokerDownload value=result.get(remaining(),TimeUnit.MILLISECONDS);remaining();value.validate();
            if(!eof || value.error==0 && received!=value.bytes)throw new IOException("TRANSFER_RECEIPT");
            payload=received;return value;
        }
        @Override public void close() {
            synchronized(this) {sessionClosed=true;if(!reading)fetching.set(false);}
            try {input.close();}catch(IOException ignored){}
            if(closed)lifecycle.post(RemoteHttpsTransport.this::reap);
        }
    }
    private void watch(Policy policy) {
        if(closed)return;
        if(SystemClock.elapsedRealtime()>=request.deadline) {fail(RuntimeResult.TIMEOUT);return;}
        BrokerHello value=hello;
        if(value!=null)try {
            BrokerProcess actual=BrokerProcess.read(value.pid);
            if(actual.start!=value.start) {fail(RuntimeResult.WORKER_DIED);return;}
            peakRss=Math.max(peakRss,actual.rss);peakCpuMs=Math.max(peakCpuMs,(actual.cpu-initialCpu)*1000/ProcessIdentity.CLOCK_TICKS);
            if(actual.rss>policy.rss) {fail(RuntimeResult.MEMORY_LIMIT);return;}
            if(peakCpuMs>policy.cpu) {fail(RuntimeResult.CPU_LIMIT);return;}
        }catch(android.system.ErrnoException unknown) {
            fail(unknown.errno==android.system.OsConstants.ENOENT || unknown.errno==android.system.OsConstants.ESRCH?RuntimeResult.WORKER_DIED:RuntimeResult.IPC_ERROR);return;
        }catch(Exception unknown) {fail(RuntimeResult.IPC_ERROR);return;}
        lifecycle.postDelayed(()->watch(policy),40);
    }
    private void fail(int code) {beginClose(code);}
    @Override public int failure() {return fatal;}
    @Override public void close() {beginClose(0);}
    private void beginClose(int code) {
        synchronized(this) {if(closed)return;fatal=code;closed=true;closeAt=SystemClock.elapsedRealtime();}
        if(code!=0)android.util.Log.w("TheKaiNHttps","BROKER_RETIRE_"+code+"_"+handshake);
        // A queued handshake must run its closed check/finally; removing it would leave a false in-flight flag.
        ready.completeExceptionally(new IOException("BROKER_RETIRED"));connectionIo.shutdown();lifecycle.post(this::reap);
        // The local write end is owned by the draining RPC task. Killing the broker releases its
        // write end; the task's finally releases ours, so a blocked read ends without fake EOF proof.
        DownloadSession active=download;if(active!=null)active.close();
    }
    private boolean serviceExists() {
        try {
            java.util.List<ActivityManager.RunningServiceInfo> services=((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getRunningServices(100);
            if(services==null)return true;
            for(ActivityManager.RunningServiceInfo value:services)
                if(value.service!=null && value.service.getPackageName().equals(context.getPackageName()) && value.service.getClassName().equals(RemoteHttpsService.class.getName()))return true;
            return false;
        }catch(RuntimeException unavailable) {return true;}
    }
    private void reap() {
        if(done)return;
        if(bound) {try {context.unbindService(connection);}catch(RuntimeException ignored){}bound=false;}
        try {context.stopService(new Intent(context,RemoteHttpsService.class));}catch(RuntimeException ignored){}
        BrokerHello value=hello;
        boolean processGone=!configured && value==null;
        if(value!=null)try {
            processGone=BrokerProcess.absent(value.pid,value.start);
            if(!processGone) {BrokerProcess actual=BrokerProcess.read(value.pid);if(actual.start==value.start)android.os.Process.killProcess(value.pid);}
        }catch(Exception unknown) {processGone=false;}
        boolean drained=processGone && connectionWorkDone && connectionIo.isTerminated() && !fetching.get() && !serviceExists() && (binder==null || binderDead || !binder.isBinderAlive());
        if(drained) {
            boolean canary=false;try {canary=("owner-only-"+request.id).equals(ProcessIdentity.read(new File(context.getFilesDir(),"https-owner-canary").getPath(),128));}catch(IOException ignored) { }
            leaseClean=canary && lease.finishKnownDead(request.id,guard);guardClosed=true;kernelGone=value!=null && processGone;serviceGone=true;
            if(leaseClean) {done=true;finishedAt=SystemClock.elapsedRealtime();thread.quitSafely();return;}
        }
        if(SystemClock.elapsedRealtime()-closeAt<2500) {lifecycle.postDelayed(this::reap,40);return;}
        guard.close();guardClosed=true;lease.poison();fatal=RuntimeResult.CLEANUP_FAILED;thread.quitSafely();
        // Keep admission reserved on uncertainty; no replacement threads or new network worker.
    }
    @Override public boolean terminated() {return closed && done && leaseClean && connectionWorkDone && connectionIo.isTerminated() && !thread.isAlive() && !fetching.get();}
    Future<Boolean> lookupSignal() {return new ReadOnlySignal<>(lookupStarted);}
    Future<Boolean> cpuSignal() {return new ReadOnlySignal<>(cpuStarted);}
    IRemoteHttps endpointForTests() {return remote;}
    public JSONObject evidence() throws Exception {
        BrokerHello value=hello;
        return new JSONObject().put("id",request.id).put("pid",value==null?0:value.pid).put("start_ticks",value==null?0:value.start)
            .put("instance",value==null?"":value.instance).put("ordinary_uid",android.os.Process.myUid()).put("owner_pid",android.os.Process.myPid())
            .put("kernel_process_gone",kernelGone).put("never_configured",!configured).put("binder_dead",binder==null || binderDead || !binder.isBinderAlive())
            .put("lease_clean",leaseClean).put("terminated",terminated()).put("failure",fatal).put("peak_rss_bytes",peakRss).put("observed_cpu_ms",peakCpuMs)
            .put("rss_source","kernel_VmRSS_kB_x_1024")
            .put("handshake_phase",handshake)
            .put("ready_notification_seen",readyNotified).put("configure_returned",configureReturned)
            .put("ready_published",ready.isDone() && !ready.isCompletedExceptionally()).put("configure_failure",configureFailure)
            .put("service_absent",serviceGone)
            .put("transfer_only",transfer)
            .put("retire_ms",finishedAt==0?-1:finishedAt-closeAt).put("requests",requests).put("payload_bytes",payload)
            .put("owner_python_loaded",ProcessIdentity.read("/proc/self/maps",2*1024*1024).contains("libpython"))
            .put("production_ready",false);
    }
    public static int recover(Context context) {
        RemoteHttpsTransport previous=LIVE.get();
        if(previous!=null && (!previous.closed || !previous.connectionWorkDone || !previous.connectionIo.isTerminated() || previous.thread.isAlive() || previous.fetching.get()))return IsolatedLease.BUSY;
        IsolatedLease lease=new IsolatedLease(context,"https");
        try {
            java.util.List<ActivityManager.RunningServiceInfo> services=((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getRunningServices(100);
            if(services==null)return IsolatedLease.UNVERIFIED;
            for(ActivityManager.RunningServiceInfo value:services)
                if(value.service!=null && value.service.getPackageName().equals(context.getPackageName()) && value.service.getClassName().equals(RemoteHttpsService.class.getName()))return IsolatedLease.BUSY;
            if(previous!=null && previous.hello!=null && !BrokerProcess.absent(previous.hello.pid,previous.hello.start))return IsolatedLease.BUSY;
            if(previous!=null && previous.binder!=null && previous.binder.isBinderAlive())return IsolatedLease.BUSY;
            int status=lease.recover().status;
            if(status==IsolatedLease.RECOVERED || status==IsolatedLease.NO_PENDING) {
                if(previous!=null && !previous.done) {previous.done=true;previous.leaseClean=true;previous.kernelGone=previous.hello!=null;previous.serviceGone=true;previous.finishedAt=SystemClock.elapsedRealtime();}
                LIVE.compareAndSet(previous,null);
            }
            return status;
        }catch(Exception unavailable) {return IsolatedLease.UNVERIFIED;}
    }
}
