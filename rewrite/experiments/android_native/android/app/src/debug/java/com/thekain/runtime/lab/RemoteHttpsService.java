package com.thekain.runtime.lab;

import android.app.Service;
import android.content.*;
import android.os.*;
import android.system.*;
import com.thekain.runtime.lab.network.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Trusted ordinary-UID network process. No Python, file paths, executable input or exported endpoint. */
public final class RemoteHttpsService extends Service {
    private static final AtomicBoolean USED=new AtomicBoolean();
    private static final String INSTANCE=UUID.randomUUID().toString();
    private final AtomicBoolean fetching=new AtomicBoolean();
    private volatile BrokerConfig config;
    private volatile IRemoteHttpsOwner owner;
    private volatile ControlledHttpsBroker.Operation operation;
    private volatile int ownerPid;
    private volatile boolean armed;
    private long sequence;private int delivered;
    private byte[] body;private ControlledHttpsBroker.Reply reply;
    static String processName(Context context) {return context.getPackageName()+":https_broker";}
    private boolean process() {
        try {
            String name=ProcessIdentity.read("/proc/self/cmdline",512).split("\u0000",2)[0];
            int uid=android.os.Process.myUid()%100000;
            return (getPackageName().equals("com.thekain.runtime.lab") || getPackageName().equals("com.thekain.video.rebuild"))
                && name.equals(processName(this)) && uid>=10000 && uid<90000;
        }catch(IOException e) {return false;}
    }
    private void die() {
        if(!process())throw new IllegalStateException("REFUSE_OWNER_KILL");
        // Do not close the kernel guard first: it stays held through all DNS/IO threads until process exit.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
    private void checkOwner() {
        if(!process() || Binder.getCallingUid()!=getApplicationInfo().uid || Binder.getCallingPid()==android.os.Process.myPid())
            throw new SecurityException("BROKER_OWNER_REQUIRED");
        try {if(!ProcessIdentity.read("/proc/"+Binder.getCallingPid()+"/cmdline",512).split("\u0000",2)[0].equals(getPackageName()))
            throw new SecurityException("BROKER_OWNER_REQUIRED");}
        catch(IOException e) {throw new SecurityException("BROKER_OWNER_REQUIRED");}
    }
    private boolean authorized(String cap) {
        BrokerConfig value=config;
        return armed && value!=null && value.id.equals(cap) && Binder.getCallingUid()==getApplicationInfo().uid
            && Binder.getCallingPid()==ownerPid && SystemClock.elapsedRealtime()<value.deadline;
    }
    private BrokerChunk failed(int code,long seq) {
        ControlledHttpsBroker.Operation op=operation;
        return BrokerChunk.failure(code,Math.max(1,seq),op==null?0:op.requestCount(),op==null?0:op.payloadBytesRead());
    }
    private final IRemoteHttps.Stub binder=new IRemoteHttps.Stub() {
        public BrokerHello hello() {
            checkOwner();try {return new BrokerHello(android.os.Process.myPid(),android.os.Process.myUid(),ProcessIdentity.selfStartTicks(),INSTANCE);}
            catch(IOException e) {throw new IllegalStateException("BROKER_IDENTITY");}
        }
        public void configure(BrokerConfig input,IRemoteHttpsOwner callback) {
            try {checkOwner();}catch(RuntimeException denied) {if(input!=null)input.close();return;}
            if(input==null || callback==null || !USED.compareAndSet(false,true)) {if(input!=null)input.close();die();return;}
            config=input;owner=callback;ownerPid=Binder.getCallingPid();
            try {
                input.validate();long now=SystemClock.elapsedRealtime();
                if(input.deadline<=now || input.deadline-now>30000 || (getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0)
                    throw new IOException("BROKER_CONFIGURATION");
                if(input.fixture!=null) {
                    java.net.URI fixture=new java.net.URI(input.fixture);
                    if(input.origins.length!=1 || !input.origins[0].equals(input.fixture) || !"https".equals(fixture.getScheme())
                        || !"127.0.0.1".equals(fixture.getHost()) || fixture.getPort()<=0 || fixture.getRawUserInfo()!=null
                        || fixture.getRawQuery()!=null || fixture.getRawFragment()!=null || !fixture.getRawPath().isEmpty())throw new IOException("BROKER_FIXTURE_SCOPE");
                }
                StructStat stat=Os.fstat(input.guard.getFileDescriptor());
                int flags=BrokerGuardFlags.flags(input.guard.getFd());
                if(!OsConstants.S_ISREG(stat.st_mode) || stat.st_size!=0 || stat.st_nlink!=1 || stat.st_uid!=getApplicationInfo().uid
                    || stat.st_dev!=input.device || stat.st_ino!=input.inode || (flags&OsConstants.O_ACCMODE)!=OsConstants.O_RDONLY)
                    throw new IOException("BROKER_GUARD");
                // Read rejects O_PATH too; public fcntlInt is API30+, so the flag query uses our libc-only helper.
                if(Os.read(input.guard.getFileDescriptor(),new byte[1],0,1)!=0)throw new IOException("BROKER_GUARD");
                if(ProcessIdentity.read("/proc/self/maps",2*1024*1024).contains("libpython"))throw new IOException("BROKER_PYTHON_FORBIDDEN");
                owner.asBinder().linkToDeath(RemoteHttpsService.this::die,0);
                ControlledHttpsBroker engine=ServiceBrokerEngine.create(new HashSet<>(Arrays.asList(input.origins)),input.fixture,input.ca,input.fault,
                    ()->{try {owner.phase(input.id,1);}catch(RemoteException e) {die();}});
                operation=engine.operationUntil(input.deadline);
                if(!owner.ready(input.id,android.os.Process.myPid(),ProcessIdentity.selfStartTicks()))throw new IOException("BROKER_READY_REFUSED");
                armed=true;owner.phase(input.id,0);
            }catch(Exception invalid) {android.util.Log.e("TheKaiNHttps","BROKER_CONFIG_REFUSED");die();}
        }
        public BrokerChunk fetch(String cap,long seq,String url,String method) {
            if(!authorized(cap))return failed(config!=null && SystemClock.elapsedRealtime()>=config.deadline?RuntimeResult.TIMEOUT:RuntimeResult.IPC_ERROR,seq);
            if(!fetching.compareAndSet(false,true))return failed(RuntimeResult.BUSY,seq);
            try {
                synchronized(RemoteHttpsService.this) {
                    if(seq!=sequence+1 || seq>12)return failed(RuntimeResult.IPC_ERROR,seq);
                    sequence=seq;body=null;reply=null;delivered=0;
                }
                if(url==null || url.length()>2048 || !("GET".equals(method)||"HEAD".equals(method)))return failed(RuntimeResult.INVALID_REQUEST,seq);
                if(config.fault==2) {die();return failed(RuntimeResult.WORKER_DIED,seq);}
                if(config.fault==3) {try {owner.phase(cap,2);}catch(RemoteException e) {die();}for(;;) {double unused=Math.sqrt(SystemClock.elapsedRealtime());}}
                ControlledHttpsBroker.Reply value=operation.fetch(url,method);
                if(!authorized(cap))return failed(RuntimeResult.TIMEOUT,seq);
                synchronized(RemoteHttpsService.this) {reply=value;body=value.body();return chunk(0);}
            }catch(BrokerFailure failed) {return failed(map(failed),seq);}
            finally {fetching.set(false);}
        }
        public BrokerChunk read(String cap,long seq,int offset) {
            if(!authorized(cap) || fetching.get())return failed(RuntimeResult.IPC_ERROR,seq);
            synchronized(RemoteHttpsService.this) {
                if(seq!=sequence || body==null || offset!=delivered || offset>=body.length)return failed(RuntimeResult.IPC_ERROR,seq);
                return chunk(offset);
            }
        }
        public void retire(String cap) {
            if(config!=null && config.id.equals(cap) && Binder.getCallingUid()==getApplicationInfo().uid && Binder.getCallingPid()==ownerPid)die();
        }
    };
    private BrokerChunk chunk(int offset) {
        int count=Math.min(BrokerChunk.CHUNK,body.length-offset);delivered=offset+count;
        return new BrokerChunk(0,sequence,reply.status,reply.finalUrl,reply.contentType,reply.declaredBytes,body.length,offset,
            Arrays.copyOfRange(body,offset,delivered),operation.requestCount(),operation.payloadBytesRead());
    }
    private static int map(BrokerFailure failed) {
        switch(failed.code) {
            case INVALID_URL:return RuntimeResult.INVALID_REQUEST;
            case OUT_OF_SCOPE:case ADDRESS_DENIED:return RuntimeResult.OUT_OF_SCOPE;
            case TIMEOUT:return RuntimeResult.TIMEOUT;
            case CANCELED:return RuntimeResult.CANCELED;
            case TLS_ERROR:return RuntimeResult.TLS_ERROR;
            case REQUEST_LIMIT:return RuntimeResult.REQUEST_LIMIT;
            case HEADER_LIMIT:case RESPONSE_LIMIT:return RuntimeResult.RESPONSE_LIMIT;
            case BUSY:case DNS_BUSY:return RuntimeResult.BUSY;
            default:return RuntimeResult.NATIVE_FAILED; // Includes unsupported framing/encoding and HTTP status; never fake a timeout.
        }
    }
    @Override public void onCreate() {super.onCreate();if(!process())throw new IllegalStateException("BROKER_PROCESS_REQUIRED");}
    @Override public IBinder onBind(Intent ignored) {return binder;}
    @Override public boolean onUnbind(Intent ignored) {die();return false;}
    @Override public void onDestroy() {die();}
}
