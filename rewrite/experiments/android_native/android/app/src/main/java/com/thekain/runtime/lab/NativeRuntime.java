package com.thekain.runtime.lab;

import android.app.ActivityManager;
import android.content.*;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Main-process supervisor. This class contains no JNI entry point or Python load. */
public final class NativeRuntime implements AutoCloseable {
    public static final long MAX_DEADLINE_MS=30000;
    private static final long SAMPLE_MS=40,CLEANUP_MS=2500;
    private final Context context;
    private final HandlerThread thread=new HandlerThread("runtime-supervisor");
    private final Handler supervisor;
    private static final AtomicBoolean GLOBAL_BUSY=new AtomicBoolean();
    private Operation active;
    private boolean closed;

    public final class Operation {
        public final String id;
        private final CompletableFuture<Outcome> result=new CompletableFuture<>();
        private final CompletableFuture<Integer> pythonStarted=new CompletableFuture<>();
        private final RuntimeRequest request;
        private final long submitted=SystemClock.elapsedRealtime();
        private final AtomicBoolean canceled=new AtomicBoolean();
        private ServiceConnection connection;
        private boolean bound,retiring,finished;
        private IBinder binder;
        private ProcessIdentity child;
        private String instance="";
        private RuntimeResult candidate;
        private long cleanupDeadline,peakRss,samples,vmBefore,addressLimit,cpuLimit,networkBytes;
        private int requests;
        private Operation(RuntimeRequest request) { this.request=request;id=request.id; }
        public CompletableFuture<Outcome> completion() { return result.thenApplyAsync(value->value); }
        public CompletableFuture<Integer> whenPythonStarted() { return pythonStarted.thenApplyAsync(value->value); }
        public boolean cancel() {
            synchronized(NativeRuntime.this) {
                if(finished || retiring)return false;
                canceled.set(true);
            }
            supervisor.post(()->retire(this,RuntimeResult.CANCELED));return true;
        }
    }
    public static final class Outcome {
        public final RuntimeResult result;
        public final String instance;
        public final int workerPid,parentPid;
        public final long startTicks,elapsedMs,peakRssBytes,samples,virtualBefore,addressLimit,cpuLimit,networkBytes;
        public final int requests;
        public final boolean deathConfirmed;
        private Outcome(Operation op,boolean dead) {
            result=op.candidate;instance=op.instance;workerPid=op.child==null?0:op.child.pid;
            parentPid=android.os.Process.myPid();startTicks=op.child==null?0:op.child.startTicks;
            elapsedMs=SystemClock.elapsedRealtime()-op.submitted;peakRssBytes=op.peakRss;samples=op.samples;
            virtualBefore=op.vmBefore;addressLimit=op.addressLimit;cpuLimit=op.cpuLimit;
            requests=op.requests;networkBytes=op.networkBytes;deathConfirmed=dead;
        }
    }
    public NativeRuntime(Context context) {
        if(ProcessIdentity.inWorker())throw new IllegalStateException("SUPERVISOR_REQUIRES_MAIN_PROCESS");
        this.context=context.getApplicationContext();thread.start();supervisor=new Handler(thread.getLooper());
    }
    public Operation submit(int operation,String url,int ca,long timeoutMs) {
        return submit(operation,url,ca,timeoutMs,RuntimeRequest.MAX_RSS,RuntimeRequest.MAX_HEADROOM);
    }
    public synchronized Operation submit(int operation,String url,int ca,long timeoutMs,long rss,long headroom) {
        String id=UUID.randomUUID().toString();RuntimeRequest request;
        try {
            if(timeoutMs<100 || timeoutMs>MAX_DEADLINE_MS)throw new IllegalArgumentException();
            request=new RuntimeRequest(id,operation,url,ca,SystemClock.elapsedRealtime()+timeoutMs,rss,headroom);
        } catch(RuntimeException e) {
            request=new RuntimeRequest(id,RuntimeRequest.PROBE,null,RuntimeRequest.CA_OWN,
                SystemClock.elapsedRealtime()+100,RuntimeRequest.MAX_RSS,RuntimeRequest.MAX_HEADROOM);
            Operation op=new Operation(request);completeImmediate(op,RuntimeResult.INVALID_REQUEST);return op;
        }
        Operation op=new Operation(request);
        if(closed || active!=null || !GLOBAL_BUSY.compareAndSet(false,true)) { completeImmediate(op,RuntimeResult.BUSY);return op; }
        active=op;supervisor.post(()->bind(op));return op;
    }
    private void completeImmediate(Operation op,int error) {
        op.candidate=new RuntimeResult(op.id,error);op.finished=true;
        op.pythonStarted.completeExceptionally(new IllegalStateException("NOT_STARTED"));
        op.result.complete(new Outcome(op,true));
    }
    private synchronized boolean current(Operation op) { return active==op && !op.finished; }
    private void bind(Operation op) {
        if(op.canceled.get()) { retire(op,RuntimeResult.CANCELED);return; }
        op.connection=new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name,IBinder binder) {
                supervisor.post(()->connected(op,binder));
            }
            @Override public void onServiceDisconnected(ComponentName name) { supervisor.post(()->died(op)); }
            @Override public void onBindingDied(ComponentName name) { supervisor.post(()->died(op)); }
            @Override public void onNullBinding(ComponentName name) { supervisor.post(()->retire(op,RuntimeResult.IPC_ERROR)); }
        };
        try {
            op.bound=context.bindService(new Intent(context,RuntimeService.class),op.connection,Context.BIND_AUTO_CREATE);
            if(!op.bound) { retire(op,RuntimeResult.IPC_ERROR);return; }
        } catch(RuntimeException e) { retire(op,RuntimeResult.IPC_ERROR);return; }
        supervisor.post(()->sample(op));
    }
    private void connected(Operation op,IBinder binder) {
        if(!current(op) || op.retiring)return;
        op.binder=binder;
        try {
            binder.linkToDeath(()->supervisor.post(()->died(op)),0);
            IRuntimeWorker.Stub.asInterface(binder).execute(op.request,callback(op));
        } catch(RemoteException|RuntimeException e) { retire(op,RuntimeResult.IPC_ERROR); }
    }
    private IRuntimeCallback callback(Operation op) {
        return new IRuntimeCallback.Stub() {
            private boolean sender(String id) {
                return current(op) && op.id.equals(id) && Binder.getCallingUid()==android.os.Process.myUid()
                    && op.child!=null && Binder.getCallingPid()==op.child.pid;
            }
            @Override public boolean ready(String id,String instance,long ticks) {
                synchronized(NativeRuntime.this) {
                    if(!current(op) || op.retiring || op.canceled.get() || !op.id.equals(id)
                            || Binder.getCallingUid()!=android.os.Process.myUid() || instance==null || instance.length()>64)return false;
                    try {
                        ProcessIdentity child=ProcessIdentity.worker(Binder.getCallingPid());
                        if(child.zombie || child.startTicks!=ticks)return false;
                        op.child=child;op.instance=instance;op.samples++;op.peakRss=Math.max(op.peakRss,child.rssBytes);return true;
                    } catch(IOException|RuntimeException e) { return false; }
                }
            }
            @Override public void phase(String id,int phase) {
                synchronized(NativeRuntime.this) {
                    if(!sender(id) || op.retiring || op.canceled.get())return;
                    if(phase==1)op.pythonStarted.complete(op.child.pid);
                }
            }
            @Override public void limits(String id,long before,long limit,long cpu) {
                synchronized(NativeRuntime.this) {
                    if(!sender(id) || op.retiring)return;
                    if(before<=0 || limit!=before+op.request.virtualHeadroom || cpu<=0 || cpu>64) {
                        supervisor.post(()->retire(op,RuntimeResult.IPC_ERROR));return;
                    }
                    op.vmBefore=before;op.addressLimit=limit;op.cpuLimit=cpu;
                }
            }
            @Override public void network(String id,int requests,long bytes) {
                synchronized(NativeRuntime.this) {
                    if(!sender(id) || op.retiring)return;
                    if(requests<op.requests || requests>ChildNetworkGate.MAX_REQUESTS || bytes<op.networkBytes
                            || bytes>ChildNetworkGate.MAX_TOTAL+1) {
                        supervisor.post(()->retire(op,RuntimeResult.IPC_ERROR));return;
                    }
                    op.requests=requests;op.networkBytes=bytes;
                }
            }
            @Override public void completed(RuntimeResult result) {
                synchronized(NativeRuntime.this) {
                    if(!current(op) || op.retiring)return;
                    if(result==null || !sender(result.id)) { supervisor.post(()->retire(op,RuntimeResult.IPC_ERROR));return; }
                    try { result.validate();op.candidate=result; }
                    catch(RuntimeException e) { op.candidate=new RuntimeResult(op.id,RuntimeResult.IPC_ERROR); }
                }
                supervisor.post(()->retire(op,-1));
            }
        };
    }
    private void findChild(Operation op) {
        if(op.child!=null)return;
        ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes=manager.getRunningAppProcesses();
        if(processes==null)return;
        for(ActivityManager.RunningAppProcessInfo info:processes) {
            if(info.uid==android.os.Process.myUid() && ProcessIdentity.NAME.equals(info.processName)) {
                try { ProcessIdentity p=ProcessIdentity.worker(info.pid);if(!p.zombie) {op.child=p;op.samples++;op.peakRss=Math.max(op.peakRss,p.rssBytes);} }
                catch(IOException|RuntimeException ignored) { }
            }
        }
    }
    private void sample(Operation op) {
        if(!current(op) || op.retiring)return;
        findChild(op);
        if(op.child!=null) {
            try {
                ProcessIdentity now=ProcessIdentity.worker(op.child.pid);
                if(!op.child.same(now) || now.zombie) { died(op);return; }
                op.samples++;op.peakRss=Math.max(op.peakRss,now.rssBytes);
                if(now.rssBytes>op.request.rssLimit) { retire(op,RuntimeResult.MEMORY_LIMIT);return; }
            } catch(IOException|RuntimeException e) { if(op.child.exited())died(op);else retire(op,RuntimeResult.IPC_ERROR);return; }
        }
        if(op.canceled.get()) { retire(op,RuntimeResult.CANCELED);return; }
        if(SystemClock.elapsedRealtime()>=op.request.deadline) { retire(op,RuntimeResult.TIMEOUT);return; }
        supervisor.postDelayed(()->sample(op),SAMPLE_MS);
    }
    private void died(Operation op) {
        if(!current(op) || op.retiring)return;
        synchronized(this) {
            if(op.candidate==null)op.candidate=new RuntimeResult(op.id,RuntimeResult.WORKER_DIED);
        }
        retire(op,-1);
    }
    private void retire(Operation op,int error) {
        synchronized(this) {
            if(!current(op) || op.retiring)return;
            op.retiring=true;
            if(op.canceled.get())error=RuntimeResult.CANCELED;
            else if(SystemClock.elapsedRealtime()>=op.request.deadline)error=RuntimeResult.TIMEOUT;
            if(error>=0)op.candidate=new RuntimeResult(op.id,error);
            if(op.candidate==null)op.candidate=new RuntimeResult(op.id,RuntimeResult.IPC_ERROR);
        }
        findChild(op);
        if(op.bound) { try {context.unbindService(op.connection);}catch(RuntimeException ignored){}op.bound=false; }
        if(op.child!=null && !op.child.exited()) { try{op.child.killOwned();}catch(IOException|RuntimeException ignored){} }
        op.cleanupDeadline=SystemClock.elapsedRealtime()+CLEANUP_MS;reap(op);
    }
    private void reap(Operation op) {
        boolean dead=op.child!=null?op.child.exited():!workerExists();
        if(!dead && SystemClock.elapsedRealtime()<op.cleanupDeadline) {
            supervisor.postDelayed(()->reap(op),SAMPLE_MS);return;
        }
        synchronized(this) {
            if(!current(op))return;
            if(!dead)op.candidate=new RuntimeResult(op.id,RuntimeResult.CLEANUP_FAILED);
            else if(op.candidate.error==RuntimeResult.OK && SystemClock.elapsedRealtime()>=op.request.deadline)
                op.candidate=new RuntimeResult(op.id,RuntimeResult.TIMEOUT);
            op.finished=true;
            // A failed reap remains a poisoned lease: never reuse an unconfirmed process.
            if(dead) {active=null;GLOBAL_BUSY.set(false);}
            Outcome outcome=new Outcome(op,dead);record(op,outcome);
            op.pythonStarted.completeExceptionally(new IllegalStateException("NOT_STARTED"));
            op.result.complete(outcome);
        }
    }
    private boolean workerExists() {
        ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> list=manager.getRunningAppProcesses();
        if(list==null)return true; // Fail closed when process death cannot be established.
        for(ActivityManager.RunningAppProcessInfo p:list)if(p.uid==android.os.Process.myUid() && ProcessIdentity.NAME.equals(p.processName))return true;
        return false;
    }
    private void record(Operation op,Outcome outcome) {
        try {
            JSONObject j=new JSONObject().put("id",op.id).put("operation",op.request.operation).put("error",outcome.result.error)
                .put("parent_pid",outcome.parentPid).put("worker_pid",outcome.workerPid).put("start_ticks",outcome.startTicks)
                .put("elapsed_ms",outcome.elapsedMs).put("peak_rss_bytes",outcome.peakRssBytes).put("rss_limit",op.request.rssLimit)
                .put("samples",outcome.samples).put("virtual_before",outcome.virtualBefore).put("rlimit_as",outcome.addressLimit)
                .put("virtual_headroom",op.request.virtualHeadroom).put("rlimit_cpu",outcome.cpuLimit).put("kernel_socket_denied",outcome.addressLimit>0).put("requests",outcome.requests).put("payload_bytes",outcome.networkBytes)
                .put("death_confirmed",outcome.deathConfirmed);
            int mode=new File(context.getFilesDir(),"process-evidence.jsonl").length()>1024*1024?Context.MODE_PRIVATE:Context.MODE_APPEND;
            try(FileOutputStream out=context.openFileOutput("process-evidence.jsonl",mode)) {
                out.write((j.toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch(Exception e) { android.util.Log.e("TheKaiNRuntime","EVIDENCE_WRITE_FAILED"); }
    }
    @Override public synchronized void close() {
        closed=true;if(active!=null && !active.finished)active.cancel();
        // Do not stop the supervisor until outstanding death/cleanup observation finishes.
        supervisor.postDelayed(()->thread.quitSafely(),CLEANUP_MS+1000);
    }
}
