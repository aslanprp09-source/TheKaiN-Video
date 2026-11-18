package com.thekain.runtime.lab;

import android.content.Context;
import android.os.Looper;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.Iterator;

/** Pure Java verified asset installation; no interpreter or native library load. */
public final class RuntimeAssets {
    private static File runtimeHome;

    private static String hash(byte[] data) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(data);
        char[] hex="0123456789abcdef".toCharArray(),out=new char[bytes.length*2];
        for(int i=0;i<bytes.length;i++) { int value=bytes[i]&255;out[i*2]=hex[value>>>4];out[i*2+1]=hex[value&15]; }
        return new String(out);
    }

    public static synchronized File prepare(Context context) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper()) throw new IllegalStateException("WORKER_THREAD_REQUIRED");
        if(runtimeHome!=null) return runtimeHome;
        String abi=Build.SUPPORTED_ABIS[0];
        if(!abi.equals("x86_64") && !abi.equals("arm64-v8a")) throw new IllegalStateException("LAB_ABI_UNSUPPORTED");
        byte[] bytes;
        try(InputStream in=context.getAssets().open("inventory-"+abi+".json")) { bytes=readBounded(in,2*1024*1024); }
        File home=new File(context.getFilesDir(),"runtime-"+hash(bytes).substring(0,16));
        if(!home.isDirectory() && !home.mkdirs()) throw new IOException("RUNTIME_DIRECTORY");
        String base=home.getCanonicalPath()+File.separator;
        JSONObject inventory=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        long total=0;
        for(Iterator<String> keys=inventory.keys();keys.hasNext();) {
            String name=keys.next();
            File file=new File(home,name);
            if(name.startsWith("/") || !file.getCanonicalPath().startsWith(base)) throw new IOException("ASSET_PATH");
            JSONObject expected=inventory.getJSONObject(name);
            long size=expected.getLong("bytes");
            total+=size;
            if(size<0 || size>32*1024*1024 || total>160*1024*1024) throw new IOException("ASSET_LIMIT");
            byte[] data;
            try(InputStream in=context.getAssets().open("runtime/"+abi+"/"+name)) { data=readBounded(in,(int)size); }
            if(data.length!=size || !hash(data).equals(expected.getString("sha256"))) throw new IOException("ASSET_DIGEST");
            if(!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) throw new IOException("ASSET_DIRECTORY");
            File temp=new File(file.getParentFile(),file.getName()+".own-lab-tmp");
            try(FileOutputStream out=new FileOutputStream(temp)) { out.write(data); }
            if(!temp.renameTo(file)) throw new IOException("ASSET_PUBLISH");
        }
        runtimeHome=home;
        return home;
    }

    private static byte[] readBounded(InputStream input,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int n;
        while((n=input.read(buffer))!=-1) { if(out.size()+n>limit) throw new IOException("ASSET_LIMIT");out.write(buffer,0,n); }
        return out.toByteArray();
    }

    private RuntimeAssets() { }
}
