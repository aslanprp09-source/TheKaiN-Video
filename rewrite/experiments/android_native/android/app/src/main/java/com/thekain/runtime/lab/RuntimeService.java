package com.thekain.runtime.lab;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One operation per process. Shared UID is crash containment, not a filesystem sandbox. */
public final class RuntimeService extends Service {
    private final AtomicBoolean used=new AtomicBoolean();
    private final String instance=UUID.randomUUID().toString();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"python-operation"));
    private static void die() {
        if(!ProcessIdentity.inWorker())throw new IllegalStateException("REFUSE_MAIN_PROCESS_KILL");
        android.os.Process.killProcess(android.os.Process.myPid());
    }
    private final IRuntimeWorker.Stub binder=new IRuntimeWorker.Stub() {
        @Override public void execute(RuntimeRequest request,IRuntimeCallback callback) {
            if(Binder.getCallingUid()!=android.os.Process.myUid() || request==null || callback==null)return;
            if(!used.compareAndSet(false,true)) { die();return; }
            try {
                request.validate();
                long remaining=request.deadline-SystemClock.elapsedRealtime();
                if(remaining<=0 || remaining>NativeRuntime.MAX_DEADLINE_MS) { die();return; }
                callback.asBinder().linkToDeath(RuntimeService::die,0);
                ProcessIdentity identity=ProcessIdentity.worker(android.os.Process.myPid());
                if(!callback.ready(request.id,instance,identity.startTicks)) { die();return; }
            } catch(Exception e) { die();return; }
            worker.execute(()->run(request,callback));
        }
    };
    private void run(RuntimeRequest request,IRuntimeCallback callback) {
        String stage="ASSETS";
        try {
            File home=RuntimeAssets.prepare(this);
            stage="JAVA_TRANSPORT";
            // The transport thread exists before the Python thread is fenced.
            try(ChildNetworkGate gate=new ChildNetworkGate(request,callback,home)) {
                stage="KERNEL_LIMIT";
                int cpu=(int)Math.max(1,(request.deadline-SystemClock.elapsedRealtime()+999)/1000)+1;
                long[] limits=ChildRuntime.configureBudget(request.virtualHeadroom,cpu);
                if(limits.length!=4 || limits[3]!=1)throw new IllegalStateException("KERNEL_FENCE_FAILED");
                callback.limits(request.id,limits[0],limits[1],limits[2]);
                if(request.operation==RuntimeRequest.BAD_REPLY) {
                    callback.completed(new RuntimeResult(UUID.randomUUID().toString(),RuntimeResult.OK));
                } else {
                    stage="PYTHON";
                    RuntimeResult result=ChildRuntime.execute(this,request,gate,home);
                    callback.completed(result);
                }
            }
        } catch(Throwable e) {
            android.util.Log.e("TheKaiNRuntime","WORKER_FAILED_"+stage+"_"+e.getClass().getSimpleName());
            try {
                int code=e instanceof OutOfMemoryError?RuntimeResult.MEMORY_LIMIT:
                    "OUTPUT_LIMIT".equals(e.getMessage())?RuntimeResult.OUTPUT_LIMIT:RuntimeResult.NATIVE_FAILED;
                callback.completed(new RuntimeResult(request.id,code));
            } catch(Exception ignored) { }
        } finally { die(); }
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public boolean onUnbind(Intent intent) { die();return false; }
    @Override public void onDestroy() { die();super.onDestroy(); }
}
