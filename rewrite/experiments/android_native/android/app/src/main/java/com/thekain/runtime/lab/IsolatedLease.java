package com.thekain.runtime.lab;

import android.content.Context;
import android.provider.Settings;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** A process-local AtomicBoolean must not silently forget unconfirmed work after owner death. */
final class IsolatedLease {
    final AtomicFile file;
    final Context context;
    IsolatedLease(Context context) {this.context=context;file=new AtomicFile(new File(context.getFilesDir(),"isolated-pending.json"));}
    private int boot() throws Exception {
        int count=Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT);
        if(count<0)throw new IOException("BOOT_COUNT_UNAVAILABLE");return count;
    }
    synchronized boolean uncertain() {
        if(!file.getBaseFile().exists() && !new File(file.getBaseFile()+".bak").exists())return false;
        try(InputStream in=file.openRead()) {
            JSONObject j=new JSONObject(new String(IsolatedResources.bytes(in,2048),StandardCharsets.UTF_8));
            int previous=j.getInt("boot");
            if(previous>=0 && previous<boot()) {file.delete();return false;} // No process can survive a real OS reboot.
            return j.getInt("owner_pid")!=android.os.Process.myPid() || j.getLong("owner_start")!=ProcessIdentity.selfStartTicks()
                || j.getBoolean("uncertain");
        }catch(Exception corruptOrUnavailable) {return true;}
    }
    synchronized void reserve(String id) throws Exception {
        JSONObject j=new JSONObject().put("boot",boot()).put("owner_pid",android.os.Process.myPid())
            .put("owner_start",ProcessIdentity.selfStartTicks()).put("id",id).put("uncertain",false);
        FileOutputStream out=file.startWrite();
        try {out.write(j.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception e) {file.failWrite(out);throw e;}
    }
    synchronized void poison() {
        // Preserve a durable refusal even if another supervisor is constructed in this owner PID.
        try {
            JSONObject j=new JSONObject().put("boot",boot()).put("owner_pid",android.os.Process.myPid())
                .put("owner_start",ProcessIdentity.selfStartTicks()).put("uncertain",true);
            FileOutputStream out=file.startWrite();
            try {out.write(j.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
            catch(Exception e) {file.failWrite(out);throw e;}
        }catch(Exception e) {android.util.Log.e("TheKaiNIsolated","UNCERTAIN_LEASE_WRITE_FAILED");}
    }
    synchronized void cleared() {file.delete();}
}
