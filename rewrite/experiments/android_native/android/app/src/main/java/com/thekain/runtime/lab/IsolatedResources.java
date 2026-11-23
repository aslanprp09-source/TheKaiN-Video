package com.thekain.runtime.lab;

import android.content.Context;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Inventory comes from this installed APK; bytes come from assets, never a mutable Python cache. */
final class IsolatedResources {
    private static final char[] HEX_DIGITS="0123456789abcdef".toCharArray();
    static String hex(byte[] data) {
        // Called for every verified asset before the operation deadline. Avoid
        // allocating a Formatter and format parser for each digest byte.
        char[] out=new char[data.length*2];
        for(int i=0;i<data.length;i++) {int value=data[i]&255;out[i*2]=HEX_DIGITS[value>>>4];out[i*2+1]=HEX_DIGITS[value&15];}
        return new String(out);
    }
    static byte[] bytes(InputStream in,int max) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;
        while((n=in.read(b))!=-1) {if(out.size()+n>max)throw new IOException("RESOURCE_LIMIT");out.write(b,0,n);}return out.toByteArray();
    }
    private static ParcelFileDescriptor snapshot(Context context,byte[] data) throws Exception {
        File file=File.createTempFile("isolated-fixture-",".tmp",context.getCacheDir());
        ParcelFileDescriptor fd=null;
        try {
            try(FileOutputStream out=new FileOutputStream(file)) {out.write(data);}
            fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
            if(!file.delete())throw new IOException("RESOURCE_UNLINK");return fd;
        }catch(Exception e) {if(fd!=null)fd.close();throw e;}
        finally {if(file.exists())file.delete();}
    }
    static IsolatedInput prepare(Context context,RuntimeRequest request,int mode,File fixtureHome,IsolatedLease.Guard guard) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("BACKGROUND_REQUIRED");
        String abi=Build.SUPPORTED_ABIS[0];
        if(!abi.equals("x86_64") && !abi.equals("arm64-v8a"))throw new IOException("ABI");
        JSONObject inventory;
        try(InputStream in=context.getAssets().open("inventory-"+abi+".json")) {
            inventory=new JSONObject(new String(bytes(in,2*1024*1024),java.nio.charset.StandardCharsets.UTF_8));
        }
        File zip=File.createTempFile("isolated-python-",".zip",context.getCacheDir());
        ParcelFileDescriptor resources=null,certificate=null,key=null;
        try {
            long total=0;Set<String> names=new HashSet<>();List<String> entries=new ArrayList<>();
            for(Iterator<String> it=inventory.keys();it.hasNext();)entries.add(it.next());Collections.sort(entries);
            try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(zip))) {
                for(String entry:entries) {
                    if(SystemClock.elapsedRealtime()>=request.deadline)throw new IOException("RESOURCE_DEADLINE");
                    String std="lib/python3.14/",site=std+"site-packages/";
                    if(!entry.startsWith(std) || entry.contains("..") || entry.contains("\\"))throw new IOException("RESOURCE_PATH");
                    String name=entry.startsWith(site)?entry.substring(site.length()):entry.substring(std.length());
                    if(name.isEmpty() || name.startsWith("/") || !names.add(name))throw new IOException("RESOURCE_COLLISION");
                    JSONObject expected=inventory.getJSONObject(entry);long length=expected.getLong("bytes");total+=length;
                    if(length<0 || length>32*1024*1024 || total>150L*1024*1024)throw new IOException("RESOURCE_BUDGET");
                    byte[] raw;
                    try(InputStream in=context.getAssets().open("runtime/"+abi+"/"+entry)) {raw=bytes(in,(int)length);}
                    if(raw.length!=length || !hex(MessageDigest.getInstance("SHA-256").digest(raw)).equals(expected.getString("sha256")))
                        throw new IOException("RESOURCE_HASH");
                    CRC32 crc=new CRC32();crc.update(raw);ZipEntry e=new ZipEntry(name);e.setMethod(ZipEntry.STORED);
                    e.setSize(raw.length);e.setCompressedSize(raw.length);e.setCrc(crc.getValue());e.setTime(0);
                    out.putNextEntry(e);out.write(raw);out.closeEntry();
                }
            }
            long size=zip.length();if(size<=0 || size>160L*1024*1024)throw new IOException("ZIP_LIMIT");
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(zip)) {byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}
            resources=ParcelFileDescriptor.open(zip,ParcelFileDescriptor.MODE_READ_ONLY);
            // These are public, ephemeral owned TLS-fixture credentials, never release signing material.
            File home=fixtureHome==null?RuntimeAssets.prepare(context):fixtureHome;
            try(InputStream in=new FileInputStream(new File(home,"fixtures/owned-ca.pem"))) {certificate=snapshot(context,bytes(in,16384));}
            try(InputStream in=new FileInputStream(new File(home,"fixtures/owned-key.pem"))) {key=snapshot(context,bytes(in,16384));}
            String digest=hex(hash.digest());if(mode==IsolatedInput.BAD_DIGEST)digest="0000000000000000000000000000000000000000000000000000000000000000";
            if(!zip.delete())throw new IOException("RESOURCE_UNLINK");
            return new IsolatedInput(request,mode,resources,certificate,key,size,digest,context.getFileStreamPath("isolated-owner-canary").getAbsolutePath(),guard);
        }catch(Exception e) {
            for(ParcelFileDescriptor fd:new ParcelFileDescriptor[]{resources,certificate,key})if(fd!=null)fd.close();throw e;
        }finally {if(zip.exists())zip.delete();}
    }
    private IsolatedResources() { }
}
