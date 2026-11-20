package com.thekain.runtime.lab;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
import android.system.StructStat;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Durable uncertainty. Explicit recovery requires an irreversible worker-held kernel lock. */
final class IsolatedLease {
    static final Object COORDINATOR=new Object();
    static final int NO_PENDING=0,RECOVERED=1,BUSY=2,UNVERIFIED=3;
    final AtomicFile file;
    final Context context;
    static final class Guard implements AutoCloseable {
        final String id;final ParcelFileDescriptor fd;final long device,inode;
        Guard(String id,ParcelFileDescriptor fd,long device,long inode) {this.id=id;this.fd=fd;this.device=device;this.inode=inode;}
        public void close() {try {fd.close();}catch(IOException ignored){}}
    }
    static final class Recovery {
        final int status;final String id;final int previousOwner;final long device,inode;
        Recovery(int status,String id,int owner,long device,long inode) {this.status=status;this.id=id;previousOwner=owner;this.device=device;this.inode=inode;}
    }
    IsolatedLease(Context context) {this.context=context;file=new AtomicFile(new File(context.getFilesDir(),"isolated-pending.json"));}
    private int boot() throws Exception {
        int count=Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT);
        if(count<0)throw new IOException("BOOT_COUNT_UNAVAILABLE");return count;
    }
    private boolean exists() {return file.getBaseFile().exists() || new File(file.getBaseFile()+".bak").exists();}
    private JSONObject read() throws Exception {
        try(InputStream in=file.openRead()) {return new JSONObject(new String(IsolatedResources.bytes(in,4096),StandardCharsets.UTF_8));}
    }
    private void write(JSONObject j) throws Exception {
        byte[] bytes=j.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>4096)throw new IOException("LEASE_BOUNDS");
        FileOutputStream out=file.startWrite();try {out.write(bytes);file.finishWrite(out);}catch(Exception e) {file.failWrite(out);throw e;}
    }
    private File guardFile(String id) throws IOException {
        try {if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}
        catch(Exception e) {throw new IOException("LEASE_ID");}
        return new File(context.getFilesDir(),"isolated-lease-"+id+".lock");
    }
    Guard createGuard(String id) throws Exception {
        ParcelFileDescriptor fd=ParcelFileDescriptor.adoptFd(LeaseNative.create(guardFile(id).getAbsolutePath()));
        try {StructStat stat=Os.fstat(fd.getFileDescriptor());return new Guard(id,fd,stat.st_dev,stat.st_ino);}
        catch(Exception e) {fd.close();guardFile(id).delete();throw e;}
    }
    boolean uncertain() {
        synchronized(COORDINATOR) {
            if(!exists())return false;
            try {
                JSONObject j=read();int previous=j.getInt("boot");
                if(previous>=0 && previous<boot()) {
                    if(j.optInt("schema")==2) {
                        File oldGuard=guardFile(j.getString("id"));
                        if(oldGuard.exists()) {
                            android.system.StructStat st=Os.lstat(oldGuard.getPath());
                            if(!OsConstants.S_ISREG(st.st_mode) || st.st_uid!=android.os.Process.myUid() || st.st_size!=0 || !oldGuard.delete())return true;
                        }
                    }
                    file.delete();return exists();
                }
                return previous!=boot() || j.getInt("owner_pid")!=android.os.Process.myPid()
                    || j.getLong("owner_start")!=ProcessIdentity.selfStartTicks() || j.getBoolean("uncertain");
            }catch(Exception corruptOrUnavailable) {return true;}
        }
    }
    // Retained for the existing synthetic, never-bound legacy lease test.
    void reserve(String id) throws Exception {synchronized(COORDINATOR) {write(base(id));}}
    private JSONObject base(String id) throws Exception {
        return new JSONObject().put("boot",boot()).put("owner_pid",android.os.Process.myPid())
            .put("owner_start",ProcessIdentity.selfStartTicks()).put("id",id).put("uncertain",false);
    }
    void reserve(String id,Guard guard) throws Exception {
        synchronized(COORDINATOR) {
            if(exists() || !id.equals(guard.id))throw new IOException("LEASE_NOT_EMPTY");
            write(base(id).put("schema",2).put("phase","RESERVED").put("device",guard.device).put("inode",guard.inode));
        }
    }
    boolean arm(String id) {
        synchronized(COORDINATOR) {try {
            JSONObject j=read();validate(j);if(!id.equals(j.getString("id")) || !j.getString("phase").equals("RESERVED")
                    || j.getBoolean("uncertain") || j.getInt("owner_pid")!=android.os.Process.myPid()
                    || j.getLong("owner_start")!=ProcessIdentity.selfStartTicks())return false;
            int proof=LeaseNative.acquire(guardFile(id).getAbsolutePath(),j.getLong("device"),j.getLong("inode"));
            if(proof>=0) {ParcelFileDescriptor.adoptFd(proof).close();return false;} // Must still be locked by the transferred description.
            write(j.put("phase","ARMED"));return true; // Durable BEFORE the child receives its Python-release ACK.
        }catch(Exception e) {return false;}}
    }
    void poison() {
        synchronized(COORDINATOR) {try {
            JSONObject j=exists()?read():base(UUID.randomUUID().toString());write(j.put("uncertain",true));
        }catch(Exception e) {android.util.Log.e("TheKaiNIsolated","UNCERTAIN_LEASE_WRITE_FAILED");}}
    }
    private void validate(JSONObject j) throws Exception {
        if(j.getInt("schema")!=2 || j.getInt("boot")!=boot() || j.getInt("owner_pid")<=0 || j.getLong("owner_start")<=0
                || j.getLong("device")<0 || j.getLong("inode")<=0)throw new IOException("LEASE_SCHEMA");
        guardFile(j.getString("id"));String phase=j.getString("phase");
        if(!phase.equals("RESERVED") && !phase.equals("ARMED") && !phase.equals("REAPED"))throw new IOException("LEASE_PHASE");
    }
    private Recovery finish(JSONObject j,boolean independentlyDead) throws Exception {
        validate(j);String id=j.getString("id");File guard=guardFile(id);String phase=j.getString("phase");
        if(!independentlyDead && !phase.equals("ARMED") && !phase.equals("REAPED"))return new Recovery(UNVERIFIED,id,0,0,0);
        int fd=-1;
        try {
            if(!guard.exists()) {
                if(!phase.equals("REAPED"))throw new IOException("LEASE_GUARD_MISSING");
            }else {
                fd=LeaseNative.acquire(guard.getAbsolutePath(),j.getLong("device"),j.getLong("inode"));
                if(fd<0)return new Recovery(BUSY,id,j.getInt("owner_pid"),j.getLong("device"),j.getLong("inode"));
                // A durable recovery commit makes a crash between unlink and journal deletion recoverable.
                write(j.put("phase","REAPED").put("uncertain",true));
                if(!guard.delete())throw new IOException("LEASE_GUARD_UNLINK");
            }
            file.delete();if(exists())throw new IOException("LEASE_JOURNAL_DELETE");
            return new Recovery(RECOVERED,id,j.getInt("owner_pid"),j.getLong("device"),j.getLong("inode"));
        }finally {if(fd>=0)ParcelFileDescriptor.adoptFd(fd).close();}
    }
    private boolean previousOwnerGone(JSONObject j) throws Exception {
        int pid=j.getInt("owner_pid");long start=j.getLong("owner_start");
        if(pid==android.os.Process.myPid() && start==ProcessIdentity.selfStartTicks())return false;
        try {
            String raw=ProcessIdentity.read("/proc/"+pid+"/stat",8192);int end=raw.lastIndexOf(')');
            if(!raw.startsWith(pid+" (") || end<0)throw new IOException("OWNER_PROC_RECORD");
            String[] fields=raw.substring(end+2).trim().split(" +");
            if(fields.length<20)throw new IOException("OWNER_PROC_FIELDS");
            return Long.parseLong(fields[19])!=start || fields[0].equals("Z") || fields[0].equals("X");
        }catch(IOException e) {
            for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof ErrnoException) {
                int code=((ErrnoException)cause).errno;if(code==OsConstants.ENOENT || code==OsConstants.ESRCH)return true;
            }
            throw e;
        }
    }
    Recovery recover() {
        synchronized(COORDINATOR) {
            if(!exists())return new Recovery(NO_PENDING,"",0,0,0);
            try {
                if(!uncertain())return new Recovery(BUSY,"",0,0,0);
                if(!exists())return new Recovery(NO_PENDING,"",0,0,0); // A real OS reboot was observed.
                JSONObject j=read();validate(j);
                boolean sameOwner=j.getInt("owner_pid")==android.os.Process.myPid() && j.getLong("owner_start")==ProcessIdentity.selfStartTicks();
                if(!sameOwner && !previousOwnerGone(j))return new Recovery(BUSY,"",0,0,0);
                if(!("owner-only-"+j.getString("id")).equals(ProcessIdentity.read(new File(context.getFilesDir(),"isolated-owner-canary").getPath(),128)))
                    return new Recovery(UNVERIFIED,"",0,0,0);
                return finish(j,false);
            }catch(Exception e) {return new Recovery(UNVERIFIED,"",0,0,0);}
        }
    }
    boolean finishKnownDead(String id,Guard guard) {
        synchronized(COORDINATOR) {try {
            if(guard!=null)guard.close();
            if(exists()) {
                JSONObject j=read();if(!id.equals(j.getString("id")))return false;
                return finish(j,true).status==RECOVERED;
            }
            // Preparation canceled before reserve/bind: no worker received this descriptor.
            if(guard!=null) {
                int fd=LeaseNative.acquire(guardFile(id).getAbsolutePath(),guard.device,guard.inode);
                if(fd<0)return false;
                try {return guardFile(id).delete();}finally {ParcelFileDescriptor.adoptFd(fd).close();}
            }
            return true;
        }catch(Exception e) {return false;}}
    }
    boolean cleared() {
        synchronized(COORDINATOR) {try {
            if(exists() && read().optInt("schema")==2)return false; // Never erase real kernel uncertainty.
            file.delete();return !exists();
        }catch(Exception e) {return false;}}
    }
}
