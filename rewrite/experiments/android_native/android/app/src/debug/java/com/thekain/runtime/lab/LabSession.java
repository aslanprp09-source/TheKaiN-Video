package com.thekain.runtime.lab;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Debug QA owner survives Activity recreation/backgrounding; never accepts intents or Python text. */
final class LabSession implements AutoCloseable {
    final Context context;
    final NativeRuntime runtime;
    private final LabQaStore store;
    private final ExecutorService owner=Executors.newSingleThreadExecutor();
    private final ExecutorService exports=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean exporting=new java.util.concurrent.atomic.AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final CompletableFuture<Void> initialized=new CompletableFuture<>();
    private JSONObject state=new JSONObject();
    private OwnedQaFixture fixture;
    private volatile Active active;
    private volatile String display="Подготовка лаборатории…",report="{}";
    private volatile boolean ready,closed,exportable;
    private long ownerStart;
    private volatile long lastHeartbeat;
    private final Runnable heartbeatTask=this::heartbeat;
    private final BroadcastReceiver powerReceiver=new BroadcastReceiver() {
        @Override public void onReceive(Context ignored,Intent intent) {event("power",false);}
    };
    static final class Active {
        final NativeRuntime.Operation operation;
        final int kind;
        final long timeoutMs;
        final CompletableFuture<NativeRuntime.Outcome> completed=new CompletableFuture<>();
        final CompletableFuture<Integer> started=new CompletableFuture<>();
        volatile ProcessIdentity child;
        JSONObject startPower;
        String requestedFault="none";
        String batterySetting="unknown";
        Active(NativeRuntime.Operation operation,int kind,long timeout) {this.operation=operation;this.kind=kind;timeoutMs=timeout;}
    }
    LabSession(Context context) {
        this.context=context.getApplicationContext();runtime=new NativeRuntime(context);store=new LabQaStore(context);
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        if(Build.VERSION.SDK_INT>=33)this.context.registerReceiver(powerReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else this.context.registerReceiver(powerReceiver,filter);
        owner.execute(this::initialize);
    }
    private void initialize() {
        try {
            ownerStart=ProcessIdentity.selfStartTicks();state=store.read();
            JSONObject pending=state.optJSONObject("pending");
            NativeRuntime.Recovery recovery=runtime.recoverBeforeUse().get(5,TimeUnit.SECONDS);
            if(recovery.error!=RuntimeResult.OK)throw new IllegalStateException("QA_RECOVERY_FAILED");
            state.put("startup_recovery",new JSONObject().put("error",recovery.error)
                .put("kill_attempts",recovery.killedWorkers).put("elapsed_ms",recovery.elapsedMs)
                .put("worker_absent_confirmed",true));
            if(pending!=null) {
                String id=pending.optString("id");if(!id.matches("[0-9a-f-]{36}"))throw new IOException("QA_PENDING_ID");
                JSONObject interrupted=new JSONObject().put("id",id).put("kind",pending.getInt("kind"))
                    .put("outcome","INTERRUPTED_OWNER_OR_SESSION").put("error",JSONObject.NULL)
                    .put("previous_parent_pid",pending.optInt("parent_pid"))
                    .put("previous_worker_pid",pending.optInt("worker_pid"))
                    .put("parent_identity_changed",pending.optInt("parent_pid")!=android.os.Process.myPid()
                        || pending.optLong("parent_start_ticks")!=ownerStart)
                    .put("death_confirmed_on_relaunch",true).put("recovery_kill_attempts",recovery.killedWorkers)
                    .put("source_commit",pending.optString("source_commit","unknown"));
                LabQaStore.append(state,"cases",interrupted,64);state.remove("pending");
            }
            state.put("device",deviceFacts());
            state.put("parent_pid",android.os.Process.myPid()).put("parent_start_ticks",ownerStart);
            state.put("source_commit",BuildConfig.QA_SOURCE_COMMIT).put("production_ready",false)
                .put("product_integration",false).put("isolation_uid_mode","shared_application_uid")
                .put("rss_limit_is_instantaneous",false).put("lab_rss_threshold_bytes",RuntimeRequest.MAX_RSS)
                .put("lab_virtual_headroom_bytes",RuntimeRequest.MAX_HEADROOM)
                .put("physical_device_attested",false).put("artifact89_review","BLOCKED_SEPARATE_GATE");
            fixture=new OwnedQaFixture(context); // Pure Java fixture; no interpreter in this PID.
            store.save(state);report=state.toString();ready=true;exportable=true;
            publish("Готово. Физическое выполнение подтверждается отдельным отчётом.");initialized.complete(null);
        } catch(Throwable failure) {fail(failure);initialized.completeExceptionally(failure);}
    }
    CompletableFuture<Void> whenReady() {return initialized.thenApplyAsync(value->value);}
    String display() {return display;}
    String report() {return report;}
    boolean ready() {return ready;}
    boolean canExport() {return exportable && active==null;}
    Active active() {return active;}
    void listen(Runnable listener) {listeners.add(listener);listener.run();}
    void unlisten(Runnable listener) {listeners.remove(listener);}
    private void publish(String message) {
        display=message+"\nPID приложения: "+android.os.Process.myPid()+"\nUID общий с Service; RSS измеряется периодически.\n"+
            "Сайты и основное приложение не подключены.";
        if(ready)report=state.toString();for(Runnable listener:listeners)main.post(listener);
    }
    private void fail(Throwable failure) {
        ready=false;
        // A corrupt journal is preserved, never copied into an exported failure diagnostic.
        try {report=new JSONObject().put("schema",1).put("source_commit",BuildConfig.QA_SOURCE_COMMIT)
            .put("qa_error_class",failure.getClass().getSimpleName()).put("production_ready",false)
            .put("product_integration",false).put("physical_device_attested",false).toString();exportable=true;}
        catch(JSONException impossible) {exportable=false;report="{}";}
        publish("Ошибка лаборатории: "+failure.getClass().getSimpleName());
    }
    CompletableFuture<NativeRuntime.Outcome> start(int kind) {
        CompletableFuture<NativeRuntime.Outcome> result=new CompletableFuture<>();
        owner.execute(()->begin(kind,result));return result;
    }
    private void begin(int kind,CompletableFuture<NativeRuntime.Outcome> result) {
        try {
            if(!ready || active!=null || closed)throw new IllegalStateException("QA_NOT_IDLE");
            if(kind!=RuntimeRequest.PROBE && kind!=RuntimeRequest.ANALYZE && kind!=RuntimeRequest.HANG
                    && kind!=RuntimeRequest.CRASH)throw new IllegalArgumentException("QA_FIXED_CASES_ONLY");
            long timeout=30000;
            NativeRuntime.Operation operation=runtime.submit(kind,kind==RuntimeRequest.ANALYZE?fixture.url:null,RuntimeRequest.CA_OWN,timeout);
            attach(new Active(operation,kind,timeout),result);
        } catch(Throwable failure) {result.completeExceptionally(failure);publish("Запуск не выполнен: "+failure.getClass().getSimpleName());}
    }
    CompletableFuture<NativeRuntime.Outcome> deadline() {
        CompletableFuture<NativeRuntime.Outcome> result=new CompletableFuture<>();
        owner.execute(()->{
            try {
                if(!ready || active!=null || closed)throw new IllegalStateException("QA_NOT_IDLE");
                attach(new Active(runtime.submit(RuntimeRequest.HANG,null,RuntimeRequest.CA_OWN,6000),RuntimeRequest.HANG,6000),result);
            } catch(Throwable failure) {result.completeExceptionally(failure);}
        });return result;
    }
    private void attach(Active operation,CompletableFuture<NativeRuntime.Outcome> result) throws Exception {
        active=operation;
        try {
            operation.startPower=power();
            operation.batterySetting=state.optString("operator_battery_setting","unknown");
            JSONObject pending=new JSONObject().put("id",operation.operation.id).put("kind",operation.kind)
                .put("parent_pid",android.os.Process.myPid()).put("parent_start_ticks",ownerStart)
                .put("source_commit",BuildConfig.QA_SOURCE_COMMIT).put("worker_pid",0)
                .put("timeout_ms",operation.timeoutMs).put("started_elapsed_ms",SystemClock.elapsedRealtime());
            state.put("pending",pending);store.save(state);
        }
        catch(Exception failure) {
            operation.operation.cancel();
            operation.operation.completion().whenComplete((out,error)->owner.execute(()->{
                active=null;operation.completed.completeExceptionally(failure);result.completeExceptionally(failure);fail(failure);
            }));return;
        }
        publish("Операция "+operation.kind+": запуск отдельного процесса.");
        main.removeCallbacks(heartbeatTask);main.post(heartbeatTask);
        operation.operation.whenPythonStarted().whenComplete((pid,error)->owner.execute(()->{
            if(active!=operation)return;
            if(error!=null) {operation.started.completeExceptionally(error);return;}
            try {
                ProcessIdentity child=ProcessIdentity.worker(pid);operation.child=child;
                JSONObject p=state.getJSONObject("pending");p.put("worker_pid",pid).put("worker_start_ticks",child.startTicks);
                store.save(state);publish("Python выполняется в PID "+pid+". Можно свернуть или заблокировать экран.");
                operation.started.complete(pid);
            } catch(Throwable failure) {operation.started.completeExceptionally(failure);}
        }));
        operation.operation.completion().whenComplete((out,error)->owner.execute(()->{
            try {
                if(active!=operation)throw new IllegalStateException("QA_STALE_OPERATION");
                if(error!=null)throw new IllegalStateException("QA_OPERATION_FAILED");
                JSONObject entry=outcome(operation,out);
                JSONObject footprint=footprint();entry.put("footprint_after",footprint);
                LabQaStore.append(state,"cases",entry,64);state.remove("pending");active=null;main.removeCallbacks(heartbeatTask);
                state.put("footprint",footprint);store.save(state);
                report=state.toString();if(!out.deathConfirmed)ready=false;
                publish("Операция завершена: код "+out.result.error+", процесс завершён: "+out.deathConfirmed+
                    "\nRSS (наблюд.): "+out.peakRssBytes/1048576+" МиБ; CPU (наблюд.): "+out.observedCpuMs+" мс"+
                    (out.result.machine==null?"":"\nJNI/CPython: "+out.result.machine+", "+out.result.pointerBits+" бит"));
                operation.completed.complete(out);result.complete(out);
            } catch(Throwable failure) {
                active=null;main.removeCallbacks(heartbeatTask);
                operation.completed.completeExceptionally(failure);result.completeExceptionally(failure);fail(failure);
            }
        }));
    }
    private void heartbeat() {
        if(active==null || closed)return;
        lastHeartbeat=SystemClock.elapsedRealtime();main.postDelayed(heartbeatTask,1000);
    }
    boolean cancel() {Active value=active;return value!=null && value.operation.cancel();}
    void killWorker() {
        owner.execute(()->{
            Active value=active;
            try {
                if(value==null || value.child==null || !value.operation.id.equals(state.getJSONObject("pending").getString("id")))
                    throw new IllegalStateException("QA_WORKER_NOT_READY");
                value.requestedFault="verified_worker_kill";value.child.killOwned();publish("Отправлено завершение только проверенному PID Service.");
            } catch(Throwable failure) {publish("Service не завершён: "+failure.getClass().getSimpleName());}
        });
    }
    void killOwnerAfterConfirmation() {
        owner.execute(()->{
            try {
                if(!BuildConfig.DEBUG || !"com.thekain.runtime.lab".equals(context.getPackageName()) || ProcessIdentity.inWorker())
                    throw new IllegalStateException("QA_OWNER_GUARD");
                Active value=active;if(value==null || value.child==null || value.kind!=RuntimeRequest.HANG)
                    throw new IllegalStateException("QA_START_HANG_FIRST");
                state.put("owner_kill_requested",true);store.save(state); // AtomicFile sync before killing this PID.
                android.os.Process.killProcess(android.os.Process.myPid());
            } catch(Throwable failure) {publish("Процесс не завершён: "+failure.getClass().getSimpleName());}
        });
    }
    void repeatAnalyses(int left) {
        if(left<1 || left>5)return;
        start(RuntimeRequest.ANALYZE).whenComplete((out,error)->{
            if(error==null && out.result.error==RuntimeResult.OK && left>1)repeatAnalyses(left-1);
        });
    }
    void operatorSettings(boolean physical,int battery) {
        owner.execute(()->{
            if(!ready || closed)return;
            try {state.put("operator_claimed_physical_device",physical).put("operator_battery_setting",
                battery==1?"standard":battery==2?"restricted":"unknown");store.save(state);publish("Условия оператора записаны; это не аттестация устройства.");}
            catch(Exception failure) {fail(failure);}
        });
    }
    CompletableFuture<Void> export(Uri uri) {
        CompletableFuture<Void> result=new CompletableFuture<>();
        if(!canExport() || !"content".equals(uri.getScheme()) || !exporting.compareAndSet(false,true)) {
            result.completeExceptionally(new IllegalStateException("QA_EXPORT_UNAVAILABLE"));return result;
        }
        String data=report;
        exports.execute(()->{
            try(OutputStream out=context.getContentResolver().openOutputStream(uri,"wt")) {
                if(out==null)throw new IOException("QA_EXPORT_OPEN");
                out.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));result.complete(null);
            } catch(Exception failure) {result.completeExceptionally(failure);}
            finally {exporting.set(false);}
        });return result;
    }
    void event(String kind,boolean focused) {
        long at=SystemClock.elapsedRealtime();
        if(closed)return;
        owner.execute(()->{
            if(!ready || closed)return;
            try {
                LabQaStore.append(state,"events",new JSONObject().put("kind",kind).put("event_elapsed_ms",at)
                    .put("observed_elapsed_ms",SystemClock.elapsedRealtime()).put("window_focused",focused).put("power",power()),128);
                store.save(state);report=state.toString();
            } catch(Exception failure) {fail(failure);}
        });
    }
    private JSONObject outcome(Active operation,NativeRuntime.Outcome out) throws Exception {
        RuntimeResult r=out.result;
        return new JSONObject().put("id",r.id).put("kind",operation.kind).put("outcome","TERMINAL").put("error",r.error)
            .put("source_commit",BuildConfig.QA_SOURCE_COMMIT).put("parent_pid",out.parentPid).put("worker_pid",out.workerPid)
            .put("requested_fault",operation.requestedFault).put("start_power",operation.startPower)
            .put("operator_battery_setting",operation.batterySetting)
            .put("worker_uid",out.workerUid).put("parent_uid",android.os.Process.myUid()).put("worker_start_ticks",out.startTicks)
            .put("worker_instance",out.instance).put("elapsed_ms",out.elapsedMs).put("timeout_ms",operation.timeoutMs)
            .put("submitted_elapsed_ms",out.submittedElapsedMs).put("finished_elapsed_ms",out.finishedElapsedMs)
            .put("deadline_lateness_ms",Math.max(0,out.elapsedMs-operation.timeoutMs))
            .put("death_confirmed",out.deathConfirmed).put("peak_sampled_rss_bytes",out.peakRssBytes).put("rss_samples",out.samples)
            .put("observed_worker_cpu_ms",out.observedCpuMs).put("cpu_ticks",out.cpuTicks).put("clock_ticks_per_second",ProcessIdentity.CLOCK_TICKS)
            .put("cancel_to_exit_ms",out.cancelToExitMs).put("retire_to_exit_ms",out.retireToExitMs)
            .put("virtual_before",out.virtualBefore).put("rlimit_as",out.addressLimit).put("rlimit_cpu",out.cpuLimit)
            .put("python",r.python).put("openssl",r.openssl).put("machine",r.machine).put("pointer_bits",r.pointerBits)
            .put("python_calls",r.pythonCalls).put("openssl_handshake",r.opensslHandshake).put("formats",r.formats)
            .put("download_enabled",r.downloadEnabled).put("main_python_loaded",ProcessIdentity.read("/proc/self/maps",2*1024*1024).contains("libpython3.14.so"))
            .put("main_heartbeat_age_ms",lastHeartbeat==0?JSONObject.NULL:SystemClock.elapsedRealtime()-lastHeartbeat).put("power",power());
    }
    private JSONObject power() throws Exception {
        PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
        ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.RunningAppProcessInfo info=new ActivityManager.RunningAppProcessInfo();ActivityManager.getMyMemoryState(info);
        return new JSONObject().put("interactive",power.isInteractive()).put("device_idle",power.isDeviceIdleMode())
            .put("power_save",power.isPowerSaveMode()).put("battery_optimization_exempt",power.isIgnoringBatteryOptimizations(context.getPackageName()))
            .put("background_restricted",Build.VERSION.SDK_INT>=28?am.isBackgroundRestricted():JSONObject.NULL)
            .put("keyguard_locked",((KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked())
            .put("process_importance",info.importance);
    }
    private JSONObject deviceFacts() throws Exception {
        JSONObject facts=new JSONObject().put("sdk",Build.VERSION.SDK_INT).put("manufacturer",Build.MANUFACTURER)
            .put("model",Build.MODEL).put("hardware",Build.HARDWARE).put("fingerprint",Build.FINGERPRINT)
            .put("abis",new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS))).put("process_64bit",android.os.Process.is64Bit())
            .put("emulator_hint",Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish")||Build.FINGERPRINT.startsWith("generic"))
            .put("apk_sha256",sha(new FileInputStream(context.getApplicationInfo().sourceDir)));
        PackageManager pm=context.getPackageManager();byte[] cert;
        if(Build.VERSION.SDK_INT>=28)cert=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners()[0].toByteArray();
        else cert=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNATURES).signatures[0].toByteArray();
        return facts.put("certificate_sha256",sha(new ByteArrayInputStream(cert)));
    }
    private static String sha(InputStream input) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=input) {byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
        StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    JSONObject footprint() throws Exception {
        long[] counts=new long[3];walk(context.getFilesDir(),context.getFilesDir().getCanonicalPath()+File.separator,counts);
        int workers=0;ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> list=am.getRunningAppProcesses();if(list==null)throw new IOException("QA_PROCESS_STATE_UNAVAILABLE");
        for(ActivityManager.RunningAppProcessInfo p:list)if(p.uid==android.os.Process.myUid() && ProcessIdentity.NAME.equals(p.processName))workers++;
        return new JSONObject().put("private_files",counts[0]).put("private_bytes",counts[1]).put("owned_temp_files",counts[2])
            .put("worker_processes",workers).put("immutable_runtime_assets_included",true);
    }
    private static void walk(File folder,String root,long[] counts) throws Exception {
        File[] files=folder.listFiles();if(files==null)throw new IOException("QA_FOOTPRINT_UNAVAILABLE");
        for(File file:files) {
            if(java.nio.file.Files.isSymbolicLink(file.toPath()) || !file.getCanonicalPath().startsWith(root))throw new IOException("QA_FOOTPRINT_PATH");
            if(file.isDirectory())walk(file,root,counts);
            else {counts[0]++;counts[1]+=file.length();if(file.getName().endsWith(".own-lab-tmp")||file.getName().endsWith(".new"))counts[2]++;}
            if(counts[0]>10000)throw new IOException("QA_FOOTPRINT_LIMIT");
        }
    }
    @Override public void close() throws Exception {
        Active value=active;if(value!=null) {value.operation.cancel();value.completed.get(35,TimeUnit.SECONDS);}
        closed=true;context.unregisterReceiver(powerReceiver);listeners.clear();main.removeCallbacks(heartbeatTask);
        owner.submit(()->{try {if(fixture!=null)fixture.close();}catch(IOException ignored){}runtime.close();}).get(5,TimeUnit.SECONDS);
        owner.shutdown();exports.shutdownNow();
    }
}
