package com.thekain.runtime.lab;

import android.app.ActivityManager;
import android.content.*;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Capability broker with libc-only lease JNI. Never loads libpython or executes Python. */
public final class IsolatedRuntime implements AutoCloseable {
    private static final AtomicBoolean LEASE=new AtomicBoolean();
    private static volatile IsolatedRuntime.Operation LIVE;
    private static final long SAMPLE=40,CLEANUP=2500;
    private final Context context;
    private final File fixtureHome;
    private final IsolatedLease lease;
    private final HandlerThread thread=new HandlerThread("isolated-supervisor");
    private final Handler supervisor;
    private final ExecutorService preparation=Executors.newSingleThreadExecutor();
    private Operation active;
    private boolean closed;
    public IsolatedRuntime(Context context) {
        this(context,null);
    }
    IsolatedRuntime(Context context,File fixtureHome) {
        try {
            if(!ProcessIdentity.read("/proc/self/cmdline",512).split("\u0000",2)[0].equals(context.getPackageName()))
                throw new IllegalStateException("OWNER_REQUIRED");
        }catch(IOException e) {throw new IllegalStateException("OWNER_IDENTITY_UNAVAILABLE",e);}
        this.context=context.getApplicationContext();this.fixtureHome=fixtureHome;lease=new IsolatedLease(this.context);
        thread.start();supervisor=new Handler(thread.getLooper());
    }
    public final class Operation {
        public final String id;
        final RuntimeRequest request;final int mode;
        final long submitted;
        final CompletableFuture<Outcome> future=new CompletableFuture<>();
        final CompletableFuture<Integer> started=new CompletableFuture<>();
        final AtomicBoolean fetching=new AtomicBoolean();
        volatile boolean canceled,retiring,finished,preparing,configuring,canaryWritten;
        volatile int requests;volatile long payload;
        volatile ChildNetworkGate gate;
        volatile Snapshot kernel;IBinder binder;IIsolatedOwner ownerEndpoint;
        IResourceOwner observerEndpoint;IIsolatedWorker worker;
        volatile int peerPid,peerUid,observerPid,observerUid;volatile long observerStart,lastSample,sequence,observerPeak,observerCpu;
        volatile boolean observerRetiring,observerNonDumpable,observerPythonLoaded,noChildConfirmed,executed;
        volatile IsolatedLease.Guard guard;boolean canaryIntact=true;volatile int childProtection;volatile int exitSignal=-1,exitCode=-1;volatile int credentialProof;
        ServiceConnection connection;
        boolean bound,connected,binderDead,pythonEntered;
        String instance="";int error=-1,denials;
        RuntimeResult result;
        long samples,peak,cpu,before,address,cpuLimit,resourceBytes,cancelAt=-1,retireAt,cleanupDeadline;
        volatile long procFailureSince=-1;
        Operation(RuntimeRequest request,int mode,long submitted) {this.request=request;this.mode=mode;id=request.id;this.submitted=submitted;}
        public CompletableFuture<Outcome> completion() {return future.thenApplyAsync(v->v);}
        public CompletableFuture<Integer> whenPythonStarted() {return started.thenApplyAsync(v->v);}
        public boolean cancel() {
            synchronized(IsolatedRuntime.this) {
                if(finished || retiring)return false;
                if(!canceled) {canceled=true;cancelAt=SystemClock.elapsedRealtime();}
            }
            supervisor.post(()->retire(this,RuntimeResult.CANCELED));return true;
        }
    }
    public static final class Outcome {
        public final RuntimeResult result;
        public final int workerPid,workerUid,observerPid,observerUid,securityDenials,requests;
        public final long observerStartTicks,observerPeakRssBytes,observerCpuTicks;
        public final boolean observerPythonLoaded,observerNonDumpable,ownerCanaryIntact;public final int childProtection,workerExitSignal,workerExitCode,credentialDenialProof;
        public final String instance;
        public final boolean deathConfirmed,pythonEntered;
        public final long startTicks,samples,peakRssBytes,cpuTicks,observedCpuMs,virtualBefore,addressLimit,cpuLimit;
        public final long elapsedMs,retireToExitMs,cancelToExitMs,payloadBytes,resourceInputBytes;
        Outcome(Operation o,boolean dead) {
            result=o.result!=null && o.error==o.result.error?o.result:new RuntimeResult(o.id,o.error);
            workerPid=o.kernel==null?0:o.kernel.pid;workerUid=o.kernel==null?-1:o.kernel.uid;
            startTicks=o.kernel==null?0:o.kernel.start;instance=o.instance;securityDenials=o.denials;
            observerPid=o.observerPid;observerUid=o.observerUid;observerStartTicks=o.observerStart;
            observerPeakRssBytes=o.observerPeak;observerCpuTicks=o.observerCpu;observerPythonLoaded=o.observerPythonLoaded;
            observerNonDumpable=o.observerNonDumpable;ownerCanaryIntact=o.canaryIntact;childProtection=o.childProtection;workerExitSignal=o.exitSignal;workerExitCode=o.exitCode;credentialDenialProof=o.credentialProof;
            deathConfirmed=dead;pythonEntered=o.pythonEntered;samples=o.samples;peakRssBytes=o.peak;
            cpuTicks=o.cpu;observedCpuMs=o.cpu*1000/ProcessIdentity.CLOCK_TICKS;
            virtualBefore=o.before;addressLimit=o.address;cpuLimit=o.cpuLimit;
            long now=SystemClock.elapsedRealtime();elapsedMs=now-o.submitted;
            retireToExitMs=now-o.retireAt;cancelToExitMs=o.cancelAt<0?-1:now-o.cancelAt;
            requests=o.requests;payloadBytes=o.payload;resourceInputBytes=o.resourceBytes;
        }
    }
    public Operation submit(int operation,String url,int ca,long timeoutMs) {
        return submit(operation,url,ca,timeoutMs,RuntimeRequest.MAX_RSS,IsolatedInput.NORMAL);
    }
    synchronized Operation submit(int operation,String url,int ca,long timeoutMs,long rss,int mode) {
        long now=SystemClock.elapsedRealtime();
        if(timeoutMs<100 || timeoutMs>NativeRuntime.MAX_DEADLINE_MS || mode<0 || mode>IsolatedInput.PLATFORM_CREDENTIAL)
            throw new IllegalArgumentException("LAB_BOUNDS");
        Operation o=new Operation(new RuntimeRequest(UUID.randomUUID().toString(),operation,url,ca,now+timeoutMs,rss,RuntimeRequest.MAX_HEADROOM),mode,now);
        synchronized(IsolatedLease.COORDINATOR) {
        if(lease.uncertain()) {
            o.error=RuntimeResult.CLEANUP_FAILED;o.retireAt=now;o.finished=true;o.future.complete(new Outcome(o,false));return o;
        }
        if(closed || active!=null || !LEASE.compareAndSet(false,true)) {
            o.error=RuntimeResult.BUSY;o.retireAt=now;o.finished=true;o.future.complete(new Outcome(o,true));return o;
        }
        active=o;LIVE=o;o.preparing=true;supervisor.post(()->sample(o));
        preparation.execute(()->prepare(o));return o;
        }
    }
    private void prepare(Operation o) {
        IsolatedInput input=null;ChildNetworkGate gate=null;
        try {
            try(FileOutputStream out=context.openFileOutput("isolated-owner-canary",Context.MODE_PRIVATE)) {
                out.write(("owner-only-"+o.id).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            o.canaryWritten=true;
            o.guard=lease.createGuard(o.id);
            input=IsolatedResources.prepare(context,o.request,o.mode,fixtureHome,o.guard);
            // Counters and all HTTPS IO belong to the trusted owner, not the isolated UID.
            gate=new ChildNetworkGate(o.request,new IRuntimeCallback.Stub() {
                public boolean ready(String id,String instance,long ticks) {return false;}
                public void phase(String id,int phase) { }
                public void limits(String id,long before,long limit,long cpu) { }
                public void completed(RuntimeResult result) { }
                public void network(String id,int requests,long bytes) {o.requests=requests;o.payload=bytes;}
            },fixtureHome==null?RuntimeAssets.prepare(context):fixtureHome);
            IsolatedInput prepared=input;ChildNetworkGate transport=gate;
            supervisor.post(()->{o.gate=transport;o.resourceBytes=prepared.bytes;o.preparing=false;
                if(!current(o) || o.retiring) {prepared.close();transport.close();return;}
                try {lease.reserve(o.id,o.guard);bind(o,prepared);}
                catch(Exception e) {prepared.close();retire(o,RuntimeResult.IPC_ERROR);}
            });
        }catch(Exception e) {
            if(input!=null)input.close();if(o.guard!=null)o.guard.close();if(gate!=null)gate.close();
            supervisor.post(()->{o.preparing=false;if(current(o) && !o.retiring)retire(o,RuntimeResult.INVALID_REQUEST);});
        }
    }
    private synchronized boolean current(Operation o) {return active==o && !o.finished;}
    private static final class Snapshot {
        final int pid,uid;final long start;final boolean dead;
        Snapshot(int pid,int uid,long start,boolean dead) {this.pid=pid;this.uid=uid;this.start=start;this.dead=dead;}
    }
    private boolean observerSender(Operation o,String cap) {
        return current(o) && o.id.equals(cap) && Binder.getCallingPid()==o.observerPid && Binder.getCallingUid()==o.observerUid;
    }
    private IResourceOwner observerCallback(Operation o) {
        return new IResourceOwner.Stub() {
            public boolean ready(String cap,long start,boolean nonDumpable) {
                synchronized(IsolatedRuntime.this) {
                    int pid=Binder.getCallingPid(),uid=Binder.getCallingUid();
                    if(!current(o) || o.observerPid!=0 || !o.id.equals(cap) || start<=0 || !nonDumpable
                            || pid<=0 || pid==android.os.Process.myPid() || uid==android.os.Process.myUid() || uid%100000<90000)return false;
                    o.observerPid=pid;o.observerUid=uid;o.observerStart=start;o.observerNonDumpable=nonDumpable;return true;
                }
            }
            public boolean sample(String cap,long sequence,long measuredAt,int pid,int uid,long start,long cpu,long rss,long vm,
                    boolean dead,long observerRss,long observerCpu,boolean pythonLoaded,int childProtection,int exitSignal,int exitCode) {
                synchronized(IsolatedRuntime.this) {
                    long now=SystemClock.elapsedRealtime();
                    if(observerSender(o,cap) && pythonLoaded) {o.observerPythonLoaded=true;return false;}
                    if(!observerSender(o,cap) || o.observerRetiring || pid!=o.peerPid || uid!=o.peerUid
                            || start<=0 || sequence!=o.sequence+1 || measuredAt>now || now-measuredAt>1000
                            || pythonLoaded || (childProtection!=0 && childProtection!=255) || observerRss<=0 || observerCpu<0
                            || (o.kernel!=null && (start!=o.kernel.start || o.kernel.dead)))return false;
                    if(exitSignal< -1 || exitSignal>64 || exitCode< -1 || exitCode>255 || (!dead && (exitSignal!=-1 || exitCode!=-1)))return false;
                    if(dead) {o.exitSignal=exitSignal;o.exitCode=exitCode;
                        if(o.mode==IsolatedInput.PLATFORM_CREDENTIAL && exitSignal==android.system.OsConstants.SIGSYS && o.credentialProof==0)o.credentialProof=3;
                    }
                    if(!dead && (rss<=0 || vm<=0 || cpu<0))return false;
                    o.childProtection=Math.max(o.childProtection,childProtection);o.sequence=sequence;o.lastSample=measuredAt;o.procFailureSince=-1;
                    o.kernel=new Snapshot(pid,uid,start,dead);
                    o.observerPeak=Math.max(o.observerPeak,observerRss);o.observerCpu=Math.max(o.observerCpu,observerCpu);
                    if(!dead) {o.samples++;o.peak=Math.max(o.peak,rss);o.cpu=Math.max(o.cpu,cpu);}
                    if(!o.retiring && (dead || rss>o.request.rssLimit))
                        supervisor.post(()->retire(o,dead?RuntimeResult.WORKER_DIED:RuntimeResult.MEMORY_LIMIT));
                    return true;
                }
            }
            public void idle(String cap) {
                synchronized(IsolatedRuntime.this) {if(observerSender(o,cap) && o.kernel==null)o.noChildConfirmed=true;}
            }
            public void failed(String cap) {
                synchronized(IsolatedRuntime.this) {
                    if(observerSender(o,cap) && o.procFailureSince<0)o.procFailureSince=SystemClock.elapsedRealtime();
                }
            }
        };
    }
    private void bind(Operation o,IsolatedInput input) {
        // Keep pending descriptors owned even if binding fails or the callback never arrives.
        oInput.put(o,input);
        o.connection=new ServiceConnection() {
            public void onServiceConnected(ComponentName component,IBinder binder) {
                supervisor.post(()->{
                    o.connected=true;o.binder=binder;
                    try {
                        binder.linkToDeath(()->supervisor.post(()->{o.binderDead=true;if(current(o) && !o.retiring)retire(o,RuntimeResult.WORKER_DIED);}),0);
                        if(current(o) && !o.retiring) {
                            o.ownerEndpoint=callback(o);o.observerEndpoint=observerCallback(o);
                            o.worker=IIsolatedWorker.Stub.asInterface(binder);o.executed=true;o.worker.execute(input,o.ownerEndpoint,o.observerEndpoint);
                            supervisor.postDelayed(()->{
                                if(current(o) && !o.retiring && o.kernel==null)retire(o,RuntimeResult.IPC_ERROR);
                            },3000);
                        }
                    }catch(Exception e) {if(current(o) && !o.retiring)retire(o,RuntimeResult.IPC_ERROR);}
                    finally {input.close();oInput.remove(o);}
                });
            }
            public void onServiceDisconnected(ComponentName component) {supervisor.post(()->{o.binderDead=true;if(current(o) && !o.retiring)retire(o,RuntimeResult.WORKER_DIED);});}
            public void onBindingDied(ComponentName component) {onServiceDisconnected(component);}
            public void onNullBinding(ComponentName component) {input.close();supervisor.post(()->retire(o,RuntimeResult.IPC_ERROR));}
        };
        try {
            o.bound=context.bindService(new Intent(context,IsolatedRuntimeService.class),o.connection,Context.BIND_AUTO_CREATE);
            if(!o.bound) {input.close();retire(o,RuntimeResult.IPC_ERROR);}
        }catch(RuntimeException e) {input.close();retire(o,RuntimeResult.IPC_ERROR);}
    }
    private final Map<Operation,IsolatedInput> oInput=new HashMap<>();
    private synchronized boolean sender(Operation o,String capability) {
        return current(o) && !o.retiring && !o.canceled && o.observerPid>0 && o.id.equals(capability)
            && Binder.getCallingPid()==o.observerPid && Binder.getCallingUid()==o.observerUid;
    }
    private IIsolatedOwner callback(Operation o) {
        return new IIsolatedOwner.Stub() {
            @Override public boolean ready(String capability,String instance,int childPid) {
                synchronized(IsolatedRuntime.this) {
                    if(!sender(o,capability) || o.peerPid!=0 || childPid<=0 || childPid==o.observerPid || childPid==android.os.Process.myPid()
                            || instance==null || !instance.matches("[0-9a-f-]{36}"))return false;
                    o.peerPid=childPid;o.peerUid=o.observerUid;o.instance=instance;return true;
                }
            }
            @Override public void phase(String capability) {
                synchronized(IsolatedRuntime.this) {if(sender(o,capability) && o.kernel!=null && o.childProtection==255) {o.pythonEntered=true;o.started.complete(o.kernel.pid);}}
            }
            @Override public boolean limits(String capability,long before,long address,long cpu,int childProtection) {
                synchronized(IsolatedRuntime.this) {
                    if(!sender(o,capability) || o.kernel==null)return false;
                    if(before<=0 || address!=before+o.request.virtualHeadroom || cpu<=0 || cpu>64 || childProtection!=255) {
                        supervisor.post(()->retire(o,RuntimeResult.IPC_ERROR));return false;
                    }
                }
                // fsync is outside the Runtime monitor: UI cancellation must remain responsive.
                if(!lease.arm(o.id)) {supervisor.post(()->retire(o,RuntimeResult.IPC_ERROR));return false;}
                synchronized(IsolatedRuntime.this) {
                    if(!sender(o,capability))return false;
                    o.before=before;o.address=address;o.cpuLimit=cpu;o.childProtection=childProtection;return true;
                }
            }
            @Override public IsolatedReply fetch(String capability,String url,String method) {
                if(!sender(o,capability) || o.kernel==null || o.gate==null)return new IsolatedReply(RuntimeResult.IPC_ERROR,null);
                if(SystemClock.elapsedRealtime()>=o.request.deadline)return new IsolatedReply(RuntimeResult.TIMEOUT,null);
                if(!("GET".equals(method)||"HEAD".equals(method)))return new IsolatedReply(RuntimeResult.INVALID_REQUEST,null);
                try {RuntimeRequest.validateUrl(url);if(o.request.url==null || new java.net.URI(url).getPort()!=new java.net.URI(o.request.url).getPort())
                    return new IsolatedReply(RuntimeResult.OUT_OF_SCOPE,null);}
                catch(Exception e) {return new IsolatedReply(RuntimeResult.OUT_OF_SCOPE,null);}
                if(!o.fetching.compareAndSet(false,true))return new IsolatedReply(RuntimeResult.BUSY,null);
                try {
                    byte[] body=o.gate.fetch(url,method);
                    if(!sender(o,capability) || SystemClock.elapsedRealtime()>=o.request.deadline)return new IsolatedReply(RuntimeResult.CANCELED,null);
                    return new IsolatedReply(RuntimeResult.OK,body);
                }catch(ChildNetworkGate.Failure e) {return new IsolatedReply(e.code,null);}
                finally {o.fetching.set(false);}
            }
            @Override public void credentialDenied(String capability,int proof) {
                synchronized(IsolatedRuntime.this) {
                    if(sender(o,capability) && o.mode==IsolatedInput.PLATFORM_CREDENTIAL && !o.pythonEntered && o.childProtection==255
                            && (proof==1 || proof==2) && o.credentialProof==0)o.credentialProof=proof;
                }
            }
            @Override public void completed(String capability,RuntimeResult result,int denials) {
                synchronized(IsolatedRuntime.this) {
                    if(!sender(o,capability) || o.result!=null)return;
                    try {
                        if(result==null || !o.id.equals(result.id) || denials<0 || denials>255)throw new IllegalArgumentException();
                        result.validate();
                        if(result.error==RuntimeResult.OK && (o.childProtection!=255 || !o.observerNonDumpable || o.procFailureSince>=0 || SystemClock.elapsedRealtime()-o.lastSample>1000 || !o.pythonEntered || o.address<=0 || o.cpuLimit<=0
                                || result.pythonCalls!=1 || denials!=(o.mode==IsolatedInput.SECURITY?255:31)))
                            throw new IllegalArgumentException("INCOMPLETE_SUCCESS_PROOF");
                        o.result=result.error==RuntimeResult.OK && o.peak>o.request.rssLimit
                            ?new RuntimeResult(o.id,RuntimeResult.MEMORY_LIMIT):result;o.denials=denials;
                    }catch(RuntimeException e) {o.result=new RuntimeResult(o.id,RuntimeResult.IPC_ERROR);}
                    supervisor.post(()->retire(o,o.result.error));
                }
            }
        };
    }
    private void sample(Operation o) {
        if(!current(o) || o.retiring)return;
        if(o.kernel!=null) {
            if(o.kernel.dead) {retire(o,RuntimeResult.WORKER_DIED);return;}
            if(o.peak>o.request.rssLimit) {retire(o,RuntimeResult.MEMORY_LIMIT);return;}
            long now=SystemClock.elapsedRealtime();
            if(now-o.lastSample>1000 || (o.procFailureSince>=0 && now-o.procFailureSince>=120)) {
                retire(o,RuntimeResult.IPC_ERROR);return;
            }
        }
        if(o.canceled) {retire(o,RuntimeResult.CANCELED);return;}
        if(SystemClock.elapsedRealtime()>=o.request.deadline) {retire(o,RuntimeResult.TIMEOUT);return;}
        supervisor.postDelayed(()->sample(o),SAMPLE);
    }
    private void retire(Operation o,int error) {
        synchronized(this) {
            if(!current(o) || o.retiring)return;o.retiring=true;o.retireAt=SystemClock.elapsedRealtime();
            o.error=o.canceled?RuntimeResult.CANCELED:o.retireAt>=o.request.deadline?RuntimeResult.TIMEOUT:error;
        }
        // Keep the trusted isolated supervisor alive until it has killed/reaped the native child.
        IsolatedInput pending=oInput.remove(o);if(pending!=null)pending.close();
        if(o.gate!=null)o.gate.close();
        try {if(o.worker!=null)o.worker.retire(o.id);}catch(Exception ignored) { }
        o.cleanupDeadline=SystemClock.elapsedRealtime()+CLEANUP;reap(o);
    }
    private boolean serviceExists(Class<?> type) {
        try {
            ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningServiceInfo> services=manager.getRunningServices(100);
            if(services==null)return true;
            for(ActivityManager.RunningServiceInfo service:services)
                if(service.service!=null && type.getName().equals(service.service.getClassName()))return true;
            return false;
        }catch(RuntimeException e) {return true;}
    }
    private void reap(Operation o) {
        if(!current(o))return;
        boolean kernelDead=o.kernel!=null?o.kernel.dead:(!o.executed || o.observerPid==0 || o.noChildConfirmed);
        if(kernelDead && !o.observerRetiring) {
            o.observerRetiring=true;
            if(o.bound) {try {context.unbindService(o.connection);}catch(RuntimeException ignored){}o.bound=false;}
            try {context.stopService(new Intent(context,IsolatedRuntimeService.class));}catch(RuntimeException ignored){}
        }
        boolean dead=kernelDead && o.observerRetiring && (o.binder==null || o.binderDead || !o.binder.isBinderAlive())
            && !serviceExists(IsolatedRuntimeService.class) && !o.preparing && !o.fetching.get() && (o.gate==null || o.gate.terminated());
        // If monitoring is lost, still retire the Service; PDEATHSIG is a fallback, never a substitute for missing proof.
        if(!kernelDead && SystemClock.elapsedRealtime()>=o.cleanupDeadline) {
            if(o.bound) {try {context.unbindService(o.connection);}catch(RuntimeException ignored){}o.bound=false;}
            try {context.stopService(new Intent(context,IsolatedRuntimeService.class));}catch(RuntimeException ignored){}
        }
        if(!dead && SystemClock.elapsedRealtime()<o.cleanupDeadline) {supervisor.postDelayed(()->reap(o),SAMPLE);return;}
        // File IO/atomic fsync runs on the supervisor thread, outside the UI-facing Runtime monitor.
        if(o.canaryWritten)try {
            o.canaryIntact=("owner-only-"+o.id).equals(ProcessIdentity.read(new File(context.getFilesDir(),"isolated-owner-canary").getPath(),128));
        }catch(IOException e) {o.canaryIntact=false;}
        boolean leaseClean=dead && o.canaryIntact && lease.finishKnownDead(o.id,o.guard);
        if(!leaseClean)lease.poison();
        synchronized(this) {
            if(!leaseClean)o.error=RuntimeResult.CLEANUP_FAILED;
            else if(o.error==0 && SystemClock.elapsedRealtime()>=o.request.deadline)o.error=RuntimeResult.TIMEOUT;
            o.finished=true;Outcome out=new Outcome(o,dead);record(o,out);
            if(leaseClean) {synchronized(IsolatedLease.COORDINATOR) {
                o.ownerEndpoint=null;o.observerEndpoint=null;o.worker=null;active=null;LIVE=null;LEASE.set(false);
            }} // Otherwise uncertainty and admission remain reserved.
            o.started.completeExceptionally(new IllegalStateException("NOT_STARTED"));o.future.complete(out);
            if(closed)thread.quitSafely();
        }
    }
    private void record(Operation o,Outcome r) {
        try {
            JSONObject j=new JSONObject().put("id",o.id).put("operation",o.request.operation).put("mode",o.mode)
                .put("error",r.result.error).put("parent_pid",android.os.Process.myPid()).put("parent_uid",android.os.Process.myUid())
                .put("worker_pid",r.workerPid).put("worker_uid",r.workerUid).put("start_ticks",r.startTicks).put("instance",r.instance)
                .put("python_entered",r.pythonEntered).put("security_denials",r.securityDenials).put("samples",r.samples)
                .put("peak_rss_bytes",r.peakRssBytes).put("rss_limit",o.request.rssLimit).put("cpu_ticks",r.cpuTicks)
                .put("clock_ticks_per_second",ProcessIdentity.CLOCK_TICKS).put("observed_cpu_ms",r.observedCpuMs)
                .put("virtual_before",r.virtualBefore).put("rlimit_as",r.addressLimit).put("rlimit_cpu",r.cpuLimit)
                .put("requests",r.requests).put("payload_bytes",r.payloadBytes).put("death_confirmed",r.deathConfirmed)
                .put("resource_input_bytes",r.resourceInputBytes).put("owner_canary_intact",r.ownerCanaryIntact)
                .put("observer_pid",r.observerPid).put("observer_uid",r.observerUid).put("observer_start_ticks",r.observerStartTicks)
                .put("observer_peak_rss_bytes",r.observerPeakRssBytes).put("observer_cpu_ticks",r.observerCpuTicks)
                .put("observer_python_loaded",r.observerPythonLoaded).put("observer_non_dumpable",r.observerNonDumpable).put("child_protection",r.childProtection).put("worker_exit_signal",r.workerExitSignal).put("worker_exit_code",r.workerExitCode).put("credential_denial_proof",r.credentialDenialProof)
                .put("elapsed_ms",r.elapsedMs).put("retire_to_exit_ms",r.retireToExitMs).put("cancel_to_exit_ms",r.cancelToExitMs);
            File file=new File(context.getFilesDir(),"isolated-evidence.jsonl");
            try(FileOutputStream out=new FileOutputStream(file,file.length()<=1024*1024)) {out.write((j+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }catch(Exception e) {android.util.Log.e("TheKaiNIsolated","EVIDENCE_WRITE_FAILED");}
    }
    public static final class RecoveryOutcome {
        public final int status,previousOwnerPid;public final String operationId;
        public final long elapsedMs,device,inode;
        RecoveryOutcome(IsolatedLease.Recovery result,long elapsed) {
            status=result.status;previousOwnerPid=result.previousOwner;operationId=result.id;
            device=result.device;inode=result.inode;elapsedMs=elapsed;
        }
        public boolean recovered() {return status==IsolatedLease.RECOVERED;}
    }
    public CompletableFuture<RecoveryOutcome> recoverInterruptedWorker() {
        CompletableFuture<RecoveryOutcome> result=new CompletableFuture<>();long started=SystemClock.elapsedRealtime();
        synchronized(this) {
            if(closed || (active!=null && !active.finished)) {
                result.complete(new RecoveryOutcome(new IsolatedLease.Recovery(IsolatedLease.BUSY,"",0,0,0),0));return result;
            }
        }
        if(!supervisor.post(()->{
            IsolatedLease.Recovery recovered;
            synchronized(IsolatedLease.COORDINATOR) {
                Operation previous=LIVE;
                boolean transportDone=previous==null || (previous.finished && !previous.preparing && !previous.fetching.get()
                    && (previous.gate==null || previous.gate.terminated()));
                if(!transportDone || serviceExists(IsolatedRuntimeService.class)) {
                    recovered=new IsolatedLease.Recovery(IsolatedLease.BUSY,"",0,0,0);
                }else {
                    // Reserve admission before deleting a recovered journal; never reset a newly admitted operation's flag.
                    if(lease.uncertain())LEASE.set(true);
                    recovered=lease.recover();
                }
            }
            if(recovered.status==IsolatedLease.RECOVERED) {
                synchronized(IsolatedRuntime.this) {synchronized(IsolatedLease.COORDINATOR) {active=null;LIVE=null;LEASE.set(false);}}
            }
            RecoveryOutcome out=new RecoveryOutcome(recovered,SystemClock.elapsedRealtime()-started);
            try {
                JSONObject proof=new JSONObject().put("status",out.status).put("operation_id",out.operationId)
                    .put("previous_owner_pid",out.previousOwnerPid).put("owner_pid",android.os.Process.myPid())
                    .put("device",out.device).put("inode",out.inode).put("elapsed_ms",out.elapsedMs)
                    .put("kernel_lock_exclusive",out.recovered()).put("old_operation_not_rewritten",true)
                    .put("production_ready",false);
                File file=new File(context.getFilesDir(),"isolated-recovery-evidence.jsonl");
                try(FileOutputStream stream=new FileOutputStream(file,file.length()<=256*1024)) {
                    stream.write((proof+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }catch(Exception e) {android.util.Log.e("TheKaiNIsolated","RECOVERY_EVIDENCE_FAILED");}
            result.complete(out);
        }))result.completeExceptionally(new IllegalStateException("RECOVERY_OWNER_CLOSED"));
        return result.thenApplyAsync(v->v);
    }
    @Override public synchronized void close() {
        closed=true;preparation.shutdownNow();
        if(active!=null && !active.finished)active.cancel();else supervisor.post(thread::quitSafely);
    }
}
