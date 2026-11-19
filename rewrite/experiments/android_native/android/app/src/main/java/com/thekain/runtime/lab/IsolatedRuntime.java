package com.thekain.runtime.lab;

import android.app.ActivityManager;
import android.content.*;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Java-only capability broker and supervisor. Never loads JNI or executes Python. */
public final class IsolatedRuntime implements AutoCloseable {
    private static final AtomicBoolean LEASE=new AtomicBoolean();
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
        volatile boolean canceled,retiring,finished,preparing;
        volatile int requests;volatile long payload;
        volatile ChildNetworkGate gate;
        KernelDescriptors kernel;IBinder binder;
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
        public final int workerPid,workerUid,securityDenials,requests;
        public final String instance;
        public final boolean deathConfirmed,pythonEntered;
        public final long startTicks,samples,peakRssBytes,cpuTicks,observedCpuMs,virtualBefore,addressLimit,cpuLimit;
        public final long elapsedMs,retireToExitMs,cancelToExitMs,payloadBytes,resourceInputBytes;
        Outcome(Operation o,boolean dead) {
            result=o.result!=null && o.error==o.result.error?o.result:new RuntimeResult(o.id,o.error);
            workerPid=o.kernel==null?0:o.kernel.pid;workerUid=o.kernel==null?-1:o.kernel.uid;
            startTicks=o.kernel==null?0:o.kernel.start;instance=o.instance;securityDenials=o.denials;
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
        if(timeoutMs<100 || timeoutMs>NativeRuntime.MAX_DEADLINE_MS || mode<0 || mode>IsolatedInput.IPC_FLOOD)
            throw new IllegalArgumentException("LAB_BOUNDS");
        Operation o=new Operation(new RuntimeRequest(UUID.randomUUID().toString(),operation,url,ca,now+timeoutMs,rss,RuntimeRequest.MAX_HEADROOM),mode,now);
        if(lease.uncertain()) {
            o.error=RuntimeResult.CLEANUP_FAILED;o.retireAt=now;o.finished=true;o.future.complete(new Outcome(o,false));return o;
        }
        if(closed || active!=null || !LEASE.compareAndSet(false,true)) {
            o.error=RuntimeResult.BUSY;o.retireAt=now;o.finished=true;o.future.complete(new Outcome(o,true));return o;
        }
        active=o;o.preparing=true;supervisor.post(()->sample(o));
        preparation.execute(()->prepare(o));return o;
    }
    private void prepare(Operation o) {
        IsolatedInput input=null;ChildNetworkGate gate=null;
        try {
            try(FileOutputStream out=context.openFileOutput("isolated-owner-canary",Context.MODE_PRIVATE)) {
                out.write(("owner-only-"+o.id).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            input=IsolatedResources.prepare(context,o.request,o.mode,fixtureHome);
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
                try {lease.reserve(o.id);bind(o,prepared);}
                catch(Exception e) {prepared.close();retire(o,RuntimeResult.IPC_ERROR);}
            });
        }catch(Exception e) {
            if(input!=null)input.close();if(gate!=null)gate.close();
            supervisor.post(()->{o.preparing=false;if(current(o) && !o.retiring)retire(o,RuntimeResult.INVALID_REQUEST);});
        }
    }
    private synchronized boolean current(Operation o) {return active==o && !o.finished;}
    private void bind(Operation o,IsolatedInput input) {
        // Keep pending descriptors owned even if binding fails or the callback never arrives.
        oInput.put(o,input);
        o.connection=new ServiceConnection() {
            public void onServiceConnected(ComponentName component,IBinder binder) {
                supervisor.post(()->{
                    o.connected=true;o.binder=binder;
                    try {
                        binder.linkToDeath(()->supervisor.post(()->{o.binderDead=true;if(current(o) && !o.retiring)retire(o,RuntimeResult.WORKER_DIED);}),0);
                        if(current(o) && !o.retiring)IIsolatedWorker.Stub.asInterface(binder).execute(input,callback(o));
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
        return current(o) && !o.retiring && !o.canceled && o.kernel!=null && o.id.equals(capability)
            && Binder.getCallingPid()==o.kernel.pid && Binder.getCallingUid()==o.kernel.uid;
    }
    private IIsolatedOwner callback(Operation o) {
        return new IIsolatedOwner.Stub() {
            @Override public boolean ready(String capability,String instance,ParcelFileDescriptor stat,ParcelFileDescriptor status) {
                try {
                    synchronized(IsolatedRuntime.this) {
                        if(!current(o) || o.retiring || o.canceled || o.kernel!=null || !o.id.equals(capability)
                                || instance==null || !instance.matches("[0-9a-f-]{36}") || stat==null || status==null)return false;
                        o.kernel=new KernelDescriptors(Binder.getCallingPid(),Binder.getCallingUid(),stat,status);
                        o.instance=instance;observe(o,o.kernel.sample());return true;
                    }
                }catch(Exception e) {
                    int errno=e instanceof android.system.ErrnoException?((android.system.ErrnoException)e).errno:0;
                    android.util.Log.e("TheKaiNIsolated","PEER_PROC_REFUSED_"+e.getClass().getSimpleName()+"_"+errno);
                    supervisor.post(()->retire(o,RuntimeResult.IPC_ERROR));return false;
                }
                finally {if(stat!=null)try {stat.close();}catch(IOException ignored){}if(status!=null)try {status.close();}catch(IOException ignored){}}
            }
            @Override public void phase(String capability) {
                synchronized(IsolatedRuntime.this) {if(sender(o,capability)) {o.pythonEntered=true;o.started.complete(o.kernel.pid);}}
            }
            @Override public void limits(String capability,long before,long address,long cpu) {
                synchronized(IsolatedRuntime.this) {
                    if(!sender(o,capability))return;
                    if(before<=0 || address!=before+o.request.virtualHeadroom || cpu<=0 || cpu>64) {
                        supervisor.post(()->retire(o,RuntimeResult.IPC_ERROR));return;
                    }
                    o.before=before;o.address=address;o.cpuLimit=cpu;
                }
            }
            @Override public IsolatedReply fetch(String capability,String url,String method) {
                if(!sender(o,capability) || o.gate==null)return new IsolatedReply(RuntimeResult.IPC_ERROR,null);
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
            @Override public void completed(String capability,RuntimeResult result,int denials) {
                synchronized(IsolatedRuntime.this) {
                    if(!sender(o,capability) || o.result!=null)return;
                    try {
                        if(result==null || !o.id.equals(result.id) || denials<0 || denials>255)throw new IllegalArgumentException();
                        result.validate();
                        if(result.error==RuntimeResult.OK && (o.procFailureSince>=0 || !o.pythonEntered || o.address<=0 || o.cpuLimit<=0
                                || result.pythonCalls!=1 || denials!=(o.mode==IsolatedInput.SECURITY?255:31)))
                            throw new IllegalArgumentException("INCOMPLETE_SUCCESS_PROOF");
                        o.result=result;o.denials=denials;
                    }catch(RuntimeException e) {o.result=new RuntimeResult(o.id,RuntimeResult.IPC_ERROR);}
                    supervisor.post(()->retire(o,o.result.error));
                }
            }
        };
    }
    private synchronized void observe(Operation o,KernelDescriptors.Sample s) {
        if(o.kernel==null || s.start!=o.kernel.start || s.uid!=o.kernel.uid || s.zombie)throw new IllegalStateException("PEER_CHANGED");
        o.procFailureSince=-1;o.samples++;o.peak=Math.max(o.peak,s.rss);o.cpu=Math.max(o.cpu,s.cpu);
    }
    private void sample(Operation o) {
        if(!current(o) || o.retiring)return;
        if(o.kernel!=null) {
            try {
                KernelDescriptors.Sample s=o.kernel.sample();
                if(s.zombie) {retire(o,RuntimeResult.WORKER_DIED);return;}
                observe(o,s);if(s.rss>o.request.rssLimit) {retire(o,RuntimeResult.MEMORY_LIMIT);return;}
            }catch(Exception e) {
                if(o.kernel.dead() || (o.binder!=null && !o.binder.isBinderAlive())) {retire(o,RuntimeResult.WORKER_DIED);return;}
                long now=SystemClock.elapsedRealtime();if(o.procFailureSince<0)o.procFailureSince=now;
                if(now-o.procFailureSince>=120) {retire(o,RuntimeResult.IPC_ERROR);return;}
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
        if(o.bound) {try {context.unbindService(o.connection);}catch(RuntimeException ignored){}o.bound=false;}
        // No cross-UID SIGKILL and no reliance on Python, Binder or worker main-loop cooperation.
        try {context.stopService(new Intent(context,IsolatedRuntimeService.class));}catch(RuntimeException ignored){}
        IsolatedInput pending=oInput.remove(o);if(pending!=null)pending.close();
        if(o.gate!=null)o.gate.close();
        o.cleanupDeadline=SystemClock.elapsedRealtime()+CLEANUP;reap(o);
    }
    private boolean serviceExists() {
        try {
            ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningServiceInfo> services=manager.getRunningServices(100);
            if(services==null)return true;
            for(ActivityManager.RunningServiceInfo service:services)
                if(service.service!=null && IsolatedRuntimeService.class.getName().equals(service.service.getClassName()))return true;
            return false;
        }catch(RuntimeException e) {return true;}
    }
    private void reap(Operation o) {
        if(!current(o))return;
        boolean kernelDead=o.kernel!=null?o.kernel.dead():o.binder==null || o.binderDead || !o.binder.isBinderAlive();
        boolean dead=kernelDead && (o.binder==null || o.binderDead || !o.binder.isBinderAlive()) && !serviceExists() && !o.preparing
            && !o.fetching.get() && (o.gate==null || o.gate.terminated());
        if(!dead && SystemClock.elapsedRealtime()<o.cleanupDeadline) {supervisor.postDelayed(()->reap(o),SAMPLE);return;}
        synchronized(this) {
            if(!dead)o.error=RuntimeResult.CLEANUP_FAILED;
            else if(o.error==0 && SystemClock.elapsedRealtime()>=o.request.deadline)o.error=RuntimeResult.TIMEOUT;
            o.finished=true;Outcome out=new Outcome(o,dead);record(o,out);
            if(o.kernel!=null)o.kernel.close();
            if(dead) {lease.cleared();active=null;LEASE.set(false);} // Otherwise poison: no uncertain worker reuse.
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
                .put("resource_input_bytes",r.resourceInputBytes)
                .put("elapsed_ms",r.elapsedMs).put("retire_to_exit_ms",r.retireToExitMs).put("cancel_to_exit_ms",r.cancelToExitMs);
            File file=new File(context.getFilesDir(),"isolated-evidence.jsonl");
            try(FileOutputStream out=new FileOutputStream(file,file.length()<=1024*1024)) {out.write((j+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }catch(Exception e) {android.util.Log.e("TheKaiNIsolated","EVIDENCE_WRITE_FAILED");}
    }
    @Override public synchronized void close() {
        closed=true;preparation.shutdownNow();
        if(active!=null && !active.finished)active.cancel();else supervisor.post(thread::quitSafely);
    }
}
