package com.thekain.runtime.lab;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Only the bounded debug QA journal; never copies fixture keys or user downloads. */
final class LabQaStore {
    private final AtomicFile file;
    static final int MAX_BYTES=256*1024;
    LabQaStore(Context context) {this(new File(context.getFilesDir(),"runtime-qa-state.json"));}
    LabQaStore(File base) {file=new AtomicFile(base);}
    JSONObject read() throws Exception {
        InputStream input;
        try {input=file.openRead();} // API26 openRead restores a surviving .bak, even when base is absent.
        catch(FileNotFoundException missing) {
            File base=file.getBaseFile();
            if(base.exists() || new File(base.getPath()+".bak").exists() || new File(base.getPath()+".new").exists())throw missing;
            return new JSONObject().put("schema",1).put("cases",new JSONArray());
        }
        try(InputStream in=input) {
            ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1) {if(data.size()+n>MAX_BYTES)throw new IOException("QA_JOURNAL_LIMIT");data.write(buffer,0,n);}
            JSONObject state=new JSONObject(data.toString("UTF-8"));
            if(state.getInt("schema")!=1 || state.getJSONArray("cases").length()>64)throw new IOException("QA_JOURNAL_SCHEMA");
            return state;
        }
    }
    void save(JSONObject value) throws Exception {
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new IOException("QA_JOURNAL_LIMIT");
        FileOutputStream out=null;
        try {out=file.startWrite();out.write(bytes);file.finishWrite(out);}
        catch(Exception failure) {if(out!=null)file.failWrite(out);throw failure;}
    }
    static void append(JSONObject state,String key,JSONObject value,int limit) throws JSONException {
        JSONArray old=state.optJSONArray(key),next=new JSONArray();
        if(old!=null)for(int index=Math.max(0,old.length()-limit+1);index<old.length();index++)next.put(old.get(index));
        next.put(value);state.put(key,next);
    }
}
