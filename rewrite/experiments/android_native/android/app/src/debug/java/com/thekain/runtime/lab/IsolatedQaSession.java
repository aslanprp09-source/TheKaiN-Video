package com.thekain.runtime.lab;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed SDK-free operator protocol, separate fixture directory and report; never a device attestation. */
final class IsolatedQaSession {
    private final Context context;
    private final AtomicFile journal;
    private final ExecutorService owner=Executors.newSingleThreadExecutor();
    private final ExecutorService exportOwner=Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy=new AtomicBoolean();
    private final AtomicBoolean exporting=new AtomicBoolean();
    private final AtomicBoolean ownerFault=new AtomicBoolean();
    private JSONObject state=new JSONObject();
    private QaAttemptJournal attempts;
    private volatile String attemptKind="";
    private volatile String awaitStage="NONE";
    private volatile String scenarioDisplay="";
    private OwnedQaFixture fixture;
    private IsolatedRuntime runtime;
    private volatile IsolatedRuntime.Operation operation;
    private volatile String display="Подготовка UID QA…",report="{}";
    private volatile boolean ready;
    IsolatedQaSession(Context context) {
        this.context=context.getApplicationContext();journal=new AtomicFile(new File(context.getFilesDir(),"isolated-qa-report.json"));
        owner.execute(this::initialize);
    }
    private void initialize() {
        try {
            if(journal.getBaseFile().exists() || new File(journal.getBaseFile()+".bak").exists())try(InputStream in=journal.openRead()) {
                state=new JSONObject(new String(IsolatedResources.bytes(in,256*1024),java.nio.charset.StandardCharsets.UTF_8));
                // Retain old evidence separately: do not attribute a previous build's rows to this APK.
                if(!BuildConfig.QA_SOURCE_COMMIT.equals(state.optString("source_commit"))) {JSONObject previous=archivePreviousBuild();state=new JSONObject().put("previous_build_evidence",previous);}
            }
            attempts=new QaAttemptJournal(state);attempts.restart();
            state.put("schema",1).put("boundary","android_isolated_uid").put("source_commit",BuildConfig.QA_SOURCE_COMMIT)
                .put("production_ready",false).put("product_integration",false).put("physical_device_attested",false)
                .put("qa89_review","BLOCKED_SEPARATE_TASK").put("rss_is_instantaneous",false)
                .put("lost_owner_recovery","EXPLICIT_KERNEL_GUARD_RECOVERY_OR_FAIL_CLOSED");
            JSONObject device=new JSONObject().put("sdk",Build.VERSION.SDK_INT).put("model",Build.MODEL)
                .put("manufacturer",Build.MANUFACTURER).put("hardware",Build.HARDWARE).put("fingerprint",Build.FINGERPRINT)
                .put("abis",new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS))).put("process_64bit",android.os.Process.is64Bit())
                .put("apk_sha256",sha(new FileInputStream(context.getApplicationInfo().sourceDir)));
            byte[] cert;
            if(Build.VERSION.SDK_INT>=28)cert=context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners()[0].toByteArray();
            else cert=context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNATURES).signatures[0].toByteArray();
            state.put("device",device.put("certificate_sha256",sha(new ByteArrayInputStream(cert))));
            File home=new File(context.getFilesDir(),"isolated-qa");
            if(!home.isDirectory() && !home.mkdirs())throw new IOException("QA_DIRECTORY");
            fixture=new OwnedQaFixture(context,home);runtime=new IsolatedRuntime(context,home);
            save();ready=true;publish("Готово. Сохраните отчёт после проверок; результаты требуют отдельного рассмотрения.");
        }catch(Exception e) {
            ready=false;report="{\"qa_error\":\"INITIALIZATION_FAILED\",\"production_ready\":false}";
            publish("Подготовка не выполнена: "+e.getClass().getSimpleName());
        }
    }
    private static String sha(InputStream input) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=input) {byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        return IsolatedResources.hex(digest.digest());
    }
    private JSONObject archivePreviousBuild() throws Exception {
        // A new APK must never attribute old rows to its own source or discard an
        // interrupted operation. Keep the original bounded journal content.
        File folder=new File(context.getFilesDir(),"isolated-qa-history");
        if(!folder.isDirectory() && !folder.mkdirs())throw new IOException("QA_HISTORY_DIRECTORY");
        String source=state.optString("source_commit");if(!source.matches("[0-9a-f]{40}"))throw new IOException("QA_HISTORY_SOURCE");
        byte[] bytes=state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        File target=new File(folder,source+".json");
        if(target.exists()) {
            if(!ProcessIdentity.read(target.getPath(),256*1024).equals(state.toString()))throw new IOException("QA_HISTORY_CONFLICT");
            return previousBuildSummary(bytes,source);
        }
        File[] old=folder.listFiles();if(old==null || old.length>=4)throw new IOException("QA_HISTORY_FULL");
        AtomicFile archive=new AtomicFile(target);FileOutputStream out=archive.startWrite();
        try {out.write(bytes);archive.finishWrite(out);}catch(Exception e) {archive.failWrite(out);throw e;}
        return previousBuildSummary(bytes,source);
    }
    private JSONObject previousBuildSummary(byte[] bytes,String source) throws Exception {
        return new JSONObject().put("source_commit",source).put("report_sha256",IsolatedResources.hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .put("operation_pending",state.has("pending")).put("failure_class",state.optString("qa_failure_class"))
            .put("archived_locally",true).put("counts_as_current_evidence",false);
    }
    private JSONObject beginScenario(String name,int kind,int mode,long deadline) throws Exception {
        awaitStage="SUBMITTING";JSONObject facts=attempts.scenario("",name);
        facts.put("operation",kind).put("mode",mode).put("requested_deadline_ms",deadline)
            .put("wait_stage","SUBMITTING").put("runtime_before_submit",runtime.diagnosticBeforeSubmit());
        scenarioDisplay="Сценарий "+facts.getInt("scenario_index")+": "+name+". Попытка "+facts.getString("attempt_id")+".";
        save();publish(scenarioDisplay);return facts;
    }
    private void submitted() throws Exception {
        state.getJSONObject("pending").put("id",operation.id);awaitStage="NONE";
        capturePending("SUBMITTED",null,0);save();
    }
    private void capturePending(String status,Future<?> awaited,long until) throws Exception {
        JSONObject pending=state.optJSONObject("pending");if(pending==null)return;
        if(!pending.optString("wait_status").equals("WAIT_EXCEEDED"))pending.put("wait_status",status);
        pending.put("wait_stage",awaitStage).put("last_capture_reason",status)
            .put("captured_elapsed_ms",SystemClock.elapsedRealtime()).put("captured_wall_ms",System.currentTimeMillis());
        if(until>0)pending.put("wait_deadline_elapsed_ms",until);
        if(awaited!=null)pending.put("awaited_future_done",awaited.isDone())
            .put("wait_source",awaited instanceof ReadOnlySignal?"READ_ONLY_RUNTIME_SIGNAL":"OTHER_FUTURE");
        if(operation!=null) {runtime.requestDiagnosticServiceObservation(operation);pending.put("runtime_snapshot",runtime.diagnosticSnapshot(operation));}
        if(status.equals("WAIT_EXCEEDED") && !pending.has("wait_exceeded_snapshot"))
            pending.put("wait_exceeded_snapshot",new JSONObject(pending.toString()));
    }
    private <T> T await(Future<T> future,int seconds,String stage) throws Exception {
        // This is the same outer wait budget, not a retry or an extended runtime
        // deadline. Persist observations while the QA executor is waiting.
        awaitStage=stage;
        return QaWait.await(future,seconds,(status,awaited,until)->{
            capturePending(status,awaited,until);if(!status.equals("RETURNED"))save();
        });
    }

    private JSONObject power() throws Exception {
        PowerManager p=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
        android.app.ActivityManager a=(android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        return new JSONObject().put("interactive",p.isInteractive()).put("device_idle",p.isDeviceIdleMode())
            .put("power_save",p.isPowerSaveMode()).put("background_restricted",Build.VERSION.SDK_INT>=28?a.isBackgroundRestricted():JSONObject.NULL);
    }
    private void save() throws Exception {
        byte[] bytes=state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(bytes.length>256*1024)throw new IOException("QA_REPORT_LIMIT");
        FileOutputStream out=journal.startWrite();try {out.write(bytes);journal.finishWrite(out);}
        catch(Exception e) {journal.failWrite(out);throw e;}report=state.toString();
    }
    private void publish(String text) {display=text;for(Runnable listener:listeners)main.post(listener);}
    void listen(Runnable listener) {listeners.add(listener);listener.run();}
    void unlisten(Runnable listener) {listeners.remove(listener);}
    String display() {return display;}
    String reportSnapshot() {return report;}
    boolean idle() {return ready && !busy.get();}
    boolean canRecover() {return runtime!=null && !busy.get();}
    boolean canKillOwner() {
        IsolatedRuntime.Operation value=operation;
        return BuildConfig.DEBUG && "MANUAL_HANG".equals(attemptKind) && !ownerFault.get() && value!=null && value.request.operation==RuntimeRequest.HANG
            && value.pythonEntered && !value.finished && !value.retiring;
    }
    void killOwnerAfterConfirmation() {
        if(!canKillOwner() || !ownerFault.compareAndSet(false,true))return;
        // The normal QA executor is awaiting HANG. Never queue this fault behind
        // that wait, clear the lease, cancel the operation or stop the Service.
        new Thread(()->{
            try {
                IsolatedRuntime.Operation value=operation;
                if(!BuildConfig.DEBUG || !"com.thekain.runtime.lab".equals(context.getPackageName())
                        || !ProcessIdentity.read("/proc/self/cmdline",512).split("\u0000",2)[0].equals(context.getPackageName())
                        || value==null || value.request.operation!=RuntimeRequest.HANG || !value.pythonEntered
                        || value.finished || value.retiring)throw new IOException("QA_OWNER_FAULT_GUARD");
                android.os.Process.killProcess(android.os.Process.myPid());
            }catch(Exception e) {publish("Процесс QA не завершён: "+e.getClass().getSimpleName());}
            finally {ownerFault.set(false);publish(display);}
        },"uid-owner-fault").start();
    }
    boolean cancellable() {return operation!=null;}
    boolean canExport() {return !exporting.get() && !"{}".equals(report);}
    void cancel() {IsolatedRuntime.Operation value=operation;if(value!=null)value.cancel();}
    void start(int kind) {
        if(!ready || !busy.compareAndSet(false,true))return;
        publish("Выполняется проверка UID…");owner.execute(()->{
            try {
                attemptKind=kind==0?"PROBE":kind==1?"SUITE":kind==2?"MANUAL_HANG":kind==3?"SUPERVISOR_RECOVERY":"DIAGNOSTIC_CYCLE";
                attempts.begin(attemptKind);save();
                if(kind==0)one(RuntimeRequest.PROBE,0,30000,false,0);
                else if(kind==3)supervisorRecoveryTrial();
                else if(kind==2)one(RuntimeRequest.HANG,IsolatedInput.BLOCK_MAIN,30000,false,-1);
                else if(kind==4) {
                    one(RuntimeRequest.HANG,IsolatedInput.BLOCK_MAIN,30000,true,1);
                    one(RuntimeRequest.HANG,IsolatedInput.BLOCK_MAIN,12000,false,2);
                    supervisorRecoveryTrial();
                }
                else {
                    one(RuntimeRequest.PROBE,0,30000,false,0);
                    one(RuntimeRequest.PROBE,IsolatedInput.SECURITY,30000,false,0);
                    one(RuntimeRequest.HANG,IsolatedInput.BLOCK_MAIN,30000,true,1);
                    one(RuntimeRequest.HANG,IsolatedInput.BLOCK_MAIN,12000,false,2);
                    one(RuntimeRequest.CRASH,0,30000,false,4);
                    one(RuntimeRequest.MEMORY,0,30000,false,7);
                    one(RuntimeRequest.MEMORY,0,30000,false,7,64L*1024*1024);
                    one(RuntimeRequest.PROBE,IsolatedInput.BAD_DIGEST,30000,false,6);
                    one(RuntimeRequest.PROBE,IsolatedInput.FAKE_PROC,30000,false,5);
                    for(int i=0;i<5;i++)one(RuntimeRequest.ANALYZE,0,30000,false,0);
                }
                attempts.completed();save();
                publish("Набор завершён. Экспортируйте JSON для проверки исходников, APK и фактических результатов.");
            }catch(Exception e) {
                ready=false;
                try {capturePending("FAILED",null,0);attempts.failed(e.getClass().getSimpleName());save();}catch(Exception ignored) { }
                publish("Проверка остановлена: "+e.getClass().getSimpleName()+". Сохраните отчёт.");
            }
            finally {operation=null;attemptKind="";busy.set(false);publish(display);}
        });
    }
    private JSONObject freshRecoveryAnalysis() throws Exception {
        beginScenario("RECOVERY_ANALYZE",RuntimeRequest.ANALYZE,0,30000);
        operation=runtime.submit(RuntimeRequest.ANALYZE,fixture.url,1,30000);submitted();
        IsolatedRuntime.Outcome fresh=await(operation.outcomeSignal(),35,"COMPLETION");
        if(fresh.result.error!=0 || !fresh.deathConfirmed || !fresh.ownerCanaryIntact || fresh.result.pythonCalls!=1
                || fresh.workerUid==android.os.Process.myUid())throw new IOException("RECOVERY_ANALYSIS_FAILED");
        File[] guards=context.getFilesDir().listFiles((directory,name)->name.startsWith("isolated-lease-") && name.endsWith(".lock"));
        if(guards==null || guards.length!=0)throw new IOException("RECOVERY_GUARD_FOOTPRINT");
        attempts.scenarioCompleted();save();operation=null;
        return new JSONObject().put("id",fresh.result.id).put("worker_uid",fresh.workerUid).put("worker_pid",fresh.workerPid)
            .put("observer_uid",fresh.observerUid).put("observer_pid",fresh.observerPid).put("error",fresh.result.error)
            .put("death_confirmed",fresh.deathConfirmed).put("owner_canary_intact",fresh.ownerCanaryIntact)
            .put("python_calls",fresh.result.pythonCalls).put("formats",fresh.result.formats).put("guard_files",guards.length)
            .put("main_python_loaded",ProcessIdentity.read("/proc/self/maps",2*1024*1024).contains("libpython3.14.so"));
    }
    private JSONObject recoveryFacts(IsolatedRuntime.RecoveryOutcome proof,String trigger) throws Exception {
        return new JSONObject().put("attempt_id",state.getString("current_attempt_id"))
            .put("scenario_id",UUID.randomUUID().toString()).put("scenario_name",trigger)
            .put("recorded_elapsed_ms",SystemClock.elapsedRealtime()).put("recorded_wall_ms",System.currentTimeMillis())
            .put("trigger",trigger).put("status",proof.status).put("kernel_lock_exclusive",proof.recovered())
            .put("operation_id",proof.operationId).put("previous_owner_pid",proof.previousOwnerPid)
            .put("owner_pid",android.os.Process.myPid()).put("parent_uid",android.os.Process.myUid())
            .put("device",proof.device).put("inode",proof.inode).put("elapsed_ms",proof.elapsedMs)
            .put("old_operation_not_rewritten",true).put("production_ready",false);
    }
    private void supervisorRecoveryTrial() throws Exception {
        beginScenario("SUPERVISOR_LOSS",RuntimeRequest.HANG,0,30000);
        operation=runtime.submit(RuntimeRequest.HANG,null,1,30000);submitted();IsolatedRuntime.Operation interrupted=operation;
        await(interrupted.pythonStartedSignal(),25,"PYTHON_STARTED");
        context.unbindService(interrupted.connection);interrupted.bound=false;
        context.stopService(new Intent(context,IsolatedRuntimeService.class));
        IsolatedRuntime.Outcome failed=await(interrupted.outcomeSignal(),6,"OBSERVER_LOSS_COMPLETION");
        if(failed.result.error!=RuntimeResult.CLEANUP_FAILED || failed.deathConfirmed)throw new IOException("OBSERVER_LOSS_NOT_PROVEN");
        IsolatedRuntime.RecoveryOutcome proof=await(runtime.recoverySignal(),4,"KERNEL_RECOVERY");
        if(!proof.recovered() || !interrupted.id.equals(proof.operationId))throw new IOException("GUARD_RECOVERY_REFUSED");
        JSONObject originalSnapshot=runtime.diagnosticSnapshot(interrupted);attempts.scenarioCompleted();save();
        JSONObject trial=recoveryFacts(proof,"supervisor_loss").put("original_snapshot",originalSnapshot).put("original_error",failed.result.error)
            .put("original_death_confirmed",failed.deathConfirmed).put("original_worker_pid",failed.workerPid)
            .put("fresh_analysis",freshRecoveryAnalysis());
        LabQaStore.append(state,"recovery_trials",trial,16);save();
    }
    void recoverOwner() {
        if(!canRecover() || !busy.compareAndSet(false,true))return;
        publish("Проверяем ядровую блокировку прежнего worker…");
        owner.execute(()->{
            try {
                attemptKind="OWNER_RECOVERY";attempts.begin(attemptKind);
                attempts.scenario("", "OWNER_RECOVERY");state.put("recovery_pending",true);save();
                IsolatedRuntime.RecoveryOutcome proof=await(runtime.recoverySignal(),4,"KERNEL_RECOVERY");
                JSONObject trial=recoveryFacts(proof,"owner_restart");attempts.scenarioCompleted();
                if(proof.recovered()) {trial.put("fresh_analysis",freshRecoveryAnalysis());ready=true;}
                LabQaStore.append(state,"recovery_trials",trial,16);state.remove("recovery_pending");attempts.completed();save();
                publish(proof.recovered()?"Ядро подтвердило освобождение блокировки. Новый анализ завершён.":
                    proof.status==IsolatedLease.NO_PENDING?"Прерванной операции нет.":"Доказательства очистки недостаточны. Новая операция не разрешена.");
            }catch(Exception e) {
                try {capturePending("FAILED",null,0);attempts.failed(e.getClass().getSimpleName());state.put("recovery_failure_class",e.getClass().getSimpleName());state.remove("recovery_pending");save();}catch(Exception ignored){}
                publish("Восстановление не подтверждено: "+e.getClass().getSimpleName());
            }finally {operation=null;attemptKind="";busy.set(false);publish(display);}
        });
    }
    private void one(int kind,int mode,long deadline,boolean cancel,int expected) throws Exception {
        one(kind,mode,deadline,cancel,expected,kind==RuntimeRequest.MEMORY?256L*1024*1024:RuntimeRequest.MAX_RSS);
    }
    private void one(int kind,int mode,long deadline,boolean cancel,int expected,long rssLimit) throws Exception {
        JSONObject before=power();
        String scenario=kind==RuntimeRequest.HANG?(cancel?"BLOCK_MAIN_CANCEL":"BLOCK_MAIN_DEADLINE"):
            kind==RuntimeRequest.PROBE?(mode==0?"PROBE":mode==1?"SECURITY":mode==IsolatedInput.BAD_DIGEST?"BAD_DIGEST":"FAKE_PROC"):
            kind==RuntimeRequest.CRASH?"CRASH":kind==RuntimeRequest.MEMORY?(rssLimit==64L*1024*1024?"RSS_64M":"RSS_DEFAULT"):"ANALYZE";
        JSONObject scenarioFacts=beginScenario(scenario,kind,mode,deadline);
        operation=runtime.submit(kind,kind==RuntimeRequest.ANALYZE?fixture.url:null,1,deadline,rssLimit,mode);submitted();
        IsolatedRuntime.Operation running=operation;
        publish(scenarioDisplay+"\nОперация UID запущена. Отмена доступна.");
        running.whenPythonStarted().whenComplete((pid,error)->{
            if(pid!=null && operation==running)publish(scenarioDisplay+"\nPython работает в отдельном UID. PID worker: "+pid+". Отмена доступна.");
        });
        if(cancel) {await(operation.pythonStartedSignal(),25,"PYTHON_STARTED");if(!operation.cancel())throw new IOException("CANCEL_NOT_ACCEPTED");}
        IsolatedRuntime.Outcome out=await(operation.outcomeSignal(),35,"COMPLETION");
        JSONObject row=new JSONObject().put("attempt_id",scenarioFacts.getString("attempt_id"))
            .put("scenario_id",scenarioFacts.getString("scenario_id")).put("scenario_name",scenarioFacts.getString("scenario_name"))
            .put("scenario_index",scenarioFacts.getInt("scenario_index"))
            .put("started_elapsed_ms",scenarioFacts.getLong("started_elapsed_ms")).put("started_wall_ms",scenarioFacts.getLong("started_wall_ms"))
            .put("finished_elapsed_ms",SystemClock.elapsedRealtime()).put("finished_wall_ms",System.currentTimeMillis())
            .put("transition_log",operation.trace.snapshot()).put("completion_wait_source","READ_ONLY_RUNTIME_SIGNAL")
            .put("id",out.result.id).put("operation",kind).put("mode",mode)
            .put("expected_error",expected).put("error",out.result.error).put("worker_pid",out.workerPid).put("worker_uid",out.workerUid)
            .put("parent_pid",android.os.Process.myPid()).put("parent_uid",android.os.Process.myUid()).put("instance",out.instance)
            .put("observer_pid",out.observerPid).put("observer_uid",out.observerUid).put("observer_start_ticks",out.observerStartTicks)
            .put("observer_peak_rss_bytes",out.observerPeakRssBytes).put("observer_cpu_ticks",out.observerCpuTicks)
            .put("observer_python_loaded",out.observerPythonLoaded).put("observer_non_dumpable",out.observerNonDumpable).put("child_protection",out.childProtection).put("worker_exit_signal",out.workerExitSignal).put("worker_exit_code",out.workerExitCode).put("credential_denial_proof",out.credentialDenialProof)
            .put("start_ticks",out.startTicks).put("death_confirmed",out.deathConfirmed).put("security_denials",out.securityDenials)
            .put("python_entered",out.pythonEntered).put("python_calls",out.result.pythonCalls).put("machine",out.result.machine)
            .put("pointer_bits",out.result.pointerBits).put("pages",out.result.pages).put("openssl_handshake",out.result.opensslHandshake)
            .put("samples",out.samples).put("peak_rss_bytes",out.peakRssBytes).put("observed_cpu_ms",out.observedCpuMs)
            .put("cpu_ticks",out.cpuTicks).put("clock_ticks_per_second",ProcessIdentity.CLOCK_TICKS)
            .put("resource_input_bytes",out.resourceInputBytes).put("owner_canary_intact",out.ownerCanaryIntact).put("rss_limit_bytes",rssLimit)
            .put("rlimit_as",out.addressLimit).put("virtual_before",out.virtualBefore).put("rlimit_cpu",out.cpuLimit)
            .put("elapsed_ms",out.elapsedMs).put("cancel_to_exit_ms",out.cancelToExitMs).put("retire_to_exit_ms",out.retireToExitMs)
            .put("requests",out.requests).put("payload_bytes",out.payloadBytes).put("power_before",before).put("power_after",power())
            .put("main_python_loaded",ProcessIdentity.read("/proc/self/maps",2*1024*1024).contains("libpython3.14.so"));
        File[] temporary=context.getCacheDir().listFiles((folder,name)->name.startsWith("isolated-python-") || name.startsWith("isolated-fixture-"));
        if(temporary==null)throw new IOException("QA_FOOTPRINT_UNAVAILABLE");row.put("owned_ipc_temp_files",temporary.length);
        LabQaStore.append(state,"cases",row,64);attempts.scenarioCompleted();save();operation=null;
        if(temporary.length!=0 || (expected>=0?out.result.error!=expected:out.result.error!=1 && out.result.error!=2) || !out.deathConfirmed || (out.result.error==0 && (out.securityDenials!=(mode==1?255:31)
                || out.workerUid==android.os.Process.myUid())))throw new IOException("QA_EXPECTATION_MISMATCH");
    }
    void export(android.net.Uri uri,java.util.function.Consumer<Boolean> result) {
        if(!exporting.compareAndSet(false,true)) {result.accept(false);return;}
        String snapshot=report;boolean running=busy.get();String stage=awaitStage;exportOwner.execute(()->{
            boolean ok=false;try(OutputStream out=context.getContentResolver().openOutputStream(uri,"wt")) {
                if(out==null)throw new IOException("EXPORT_UNAVAILABLE");
                JSONObject exportState=new JSONObject(snapshot).put("exported_while_busy",running).put("export_wait_stage",stage);
                if(runtime!=null)exportState.put("export_runtime_observation",runtime.diagnosticBeforeSubmit());
                byte[] bytes=exportState.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if(bytes.length>LabQaStore.MAX_BYTES)throw new IOException("QA_EXPORT_LIMIT");out.write(bytes);ok=true;
            }catch(Exception ignored){}finally {exporting.set(false);}boolean saved=ok;main.post(()->result.accept(saved));
        });
    }
}
