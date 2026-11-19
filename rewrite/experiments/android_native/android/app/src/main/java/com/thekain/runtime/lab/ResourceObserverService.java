package com.thekain.runtime.lab;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Java-only, separate isolated UID. No Python, owner storage or network capabilities. */
public final class ResourceObserverService extends Service {
    static final String NAME="com.thekain.runtime.lab:resource_observer";
    static final String COMPONENT_PROCESS_NAME=NAME+":com.thekain.runtime.lab.ResourceObserverService";
    private static final AtomicBoolean USED=new AtomicBoolean();
    private static volatile IBinder processBinder;
    private final HandlerThread thread=new HandlerThread("kernel-observer");
    private Handler monitor;
    private IResourceOwner owner;
    private String capability;
    private int workerPid,workerUid;
    private KernelDescriptors kernel;
    private long sequence;
    private boolean attached,terminal;
    static boolean inObserver() {
        try {
            String name=ProcessIdentity.read("/proc/self/cmdline",512).split("\u0000",2)[0];
            return (NAME.equals(name) || COMPONENT_PROCESS_NAME.equals(name)) && android.os.Process.myUid()%100000>=90000;
        }catch(IOException e) {return false;}
    }
    private static void die() {
        if(!inObserver())throw new IllegalStateException("REFUSE_OWNER_KILL");
        android.os.Process.killProcess(android.os.Process.myPid());
    }
    private boolean ownerCaller() {return Binder.getCallingUid()==getApplicationInfo().uid;}
    private final IKernelSink.Stub sink=new IKernelSink.Stub() {
        @Override public boolean attach(String cap,ParcelFileDescriptor stat,ParcelFileDescriptor status) {
            try {
                synchronized(ResourceObserverService.this) {
                    if(owner==null || attached || !capability.equals(cap) || workerPid<=0
                            || Binder.getCallingPid()!=workerPid || Binder.getCallingUid()!=workerUid
                            || stat==null || status==null)return false;
                    // KernelDescriptors authenticates the actual sender, read-only FD links and kernel UID/start.
                    kernel=new KernelDescriptors(workerPid,workerUid,stat,status);attached=true;
                    if(!emit(kernel.sample(),false))return false;
                    monitor.post(ResourceObserverService.this::poll);return true;
                }
            }catch(Exception e) {
                android.util.Log.e("TheKaiNIsolated","OBSERVER_ATTACH_REFUSED_"+e.getClass().getSimpleName());
                return false;
            }finally {
                if(stat!=null)try {stat.close();}catch(IOException ignored) { }
                if(status!=null)try {status.close();}catch(IOException ignored) { }
            }
        }
    };
    private final IResourceObserver.Stub binder=new IResourceObserver.Stub() {
        @Override public IKernelSink prepare(String cap,IResourceOwner callback) {
            if(!ownerCaller() || !inObserver() || callback==null || cap==null || !cap.matches("[0-9a-f-]{36}")
                    || !USED.compareAndSet(false,true))return null;
            synchronized(ResourceObserverService.this) {
                capability=cap;owner=callback;
                try {
                    owner.asBinder().linkToDeath(ResourceObserverService::die,0);
                    // Guard JNI in this exact process, and verify the native interpreter is absent.
                    try {Class.forName("com.thekain.runtime.lab.IsolatedChild");return null;}
                    catch(ExceptionInInitializerError denied) {
                        if(!(denied.getCause() instanceof IllegalStateException))return null;
                    }
                    if(pythonLoaded() || !owner.ready(cap,ProcessIdentity.selfStartTicks()))return null;
                    return sink;
                }catch(Exception e) {
                    android.util.Log.e("TheKaiNIsolated","OBSERVER_PREPARE_REFUSED_"+e.getClass().getSimpleName());return null;
                }
            }
        }
        @Override public boolean expectWorker(String cap,int pid,int uid) {
            if(!ownerCaller())return false;
            synchronized(ResourceObserverService.this) {
                if(owner==null || workerPid!=0 || !capability.equals(cap) || pid<=0 || pid==android.os.Process.myPid()
                        || uid==android.os.Process.myUid() || uid%100000<90000)return false;
                workerPid=pid;workerUid=uid;return true;
            }
        }
    };
    private static boolean pythonLoaded() throws IOException {
        return ProcessIdentity.read("/proc/self/maps",2*1024*1024).contains("libpython3.14.so");
    }
    private boolean emit(KernelDescriptors.Sample sample,boolean dead) throws Exception {
        KernelDescriptors.Sample self=KernelDescriptors.selfSample();
        return owner.sample(capability,++sequence,SystemClock.elapsedRealtime(),workerPid,workerUid,
            kernel.start,sample==null?0:sample.cpu,sample==null?0:sample.rss,sample==null?0:sample.vm,dead,
            self.rss,self.cpu,pythonLoaded());
    }
    private synchronized void poll() {
        if(terminal || kernel==null)return;
        try {
            KernelDescriptors.Sample sample=kernel.sample();
            if(sample.zombie) {terminal=true;emit(sample,true);return;}
            if(!emit(sample,false)) {terminal=true;return;}
        }catch(Exception e) {
            if(kernel.dead()) {
                try {terminal=true;emit(null,true);}catch(Exception ignored) { }
                return;
            }
            if(sequence==1)android.util.Log.e("TheKaiNIsolated","OBSERVER_SAMPLE_REFUSED_"+e.getClass().getSimpleName());
            // A transient exiting task may expose incomplete status before ESRCH. Never emit success.
            try {owner.failed(capability);}catch(Exception ignored) { }
        }
        monitor.postDelayed(this::poll,40);
    }
    @Override public void onCreate() {super.onCreate();thread.start();monitor=new Handler(thread.getLooper());}
    @Override public IBinder onBind(Intent intent) {
        if(processBinder!=null && processBinder!=binder) {die();return null;}
        processBinder=binder;return binder;
    }
    @Override public boolean onUnbind(Intent intent) {return false;}
}
