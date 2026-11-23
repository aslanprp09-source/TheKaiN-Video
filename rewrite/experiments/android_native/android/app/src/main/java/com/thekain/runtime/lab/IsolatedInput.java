package com.thekain.runtime.lab;

import android.os.*;
import java.io.*;

/** Three read-only resources and one zero-length read-only kernel guard. Never a directory, code string or writable FD. */
public final class IsolatedInput implements Parcelable,AutoCloseable {
    static final int NORMAL=0,SECURITY=1,BLOCK_MAIN=2,FAKE_PROC=3,BAD_DIGEST=4,IPC_FLOOD=5,CHILD_FRAME=6,PLATFORM_CREDENTIAL=7;
    final RuntimeRequest request;
    final int mode;
    final ParcelFileDescriptor resources,certificate,key,guard;
    final long bytes,guardDevice,guardInode;
    final String sha256,canaryPath;
    IsolatedInput(RuntimeRequest request,int mode,ParcelFileDescriptor resources,
            ParcelFileDescriptor certificate,ParcelFileDescriptor key,long bytes,String sha256,String canaryPath,IsolatedLease.Guard leaseGuard) {
        this.request=request;this.mode=mode;this.resources=resources;this.certificate=certificate;this.key=key;
        guard=leaseGuard.fd;guardDevice=leaseGuard.device;guardInode=leaseGuard.inode;
        this.bytes=bytes;this.sha256=sha256;this.canaryPath=canaryPath;validate();
    }
    private IsolatedInput(Parcel p) {
        request=RuntimeRequest.CREATOR.createFromParcel(p);mode=p.readInt();bytes=p.readLong();
        sha256=Wire.string(p,64,false);canaryPath=Wire.string(p,512,false);guardDevice=p.readLong();guardInode=p.readLong();
        ParcelFileDescriptor[] fds=readDescriptors(p);resources=fds[0];certificate=fds[1];key=fds[2];guard=fds[3];
        try {validate();}catch(RuntimeException e) {close();throw e;}
    }
    private static ParcelFileDescriptor[] readDescriptors(Parcel p) {
        ParcelFileDescriptor[] fds=new ParcelFileDescriptor[4];
        try {for(int i=0;i<4;i++)fds[i]=ParcelFileDescriptor.CREATOR.createFromParcel(p);return fds;}
        catch(RuntimeException e) {for(ParcelFileDescriptor fd:fds)if(fd!=null)try {fd.close();}catch(IOException ignored){}throw e;}
    }
    void validate() {
        Wire.check(request!=null);request.validate();Wire.check(mode>=NORMAL && mode<=PLATFORM_CREDENTIAL);
        Wire.check(guard!=null && guardDevice>=0 && guardInode>0);
        Wire.check(resources!=null && certificate!=null && key!=null && bytes>0 && bytes<=160L*1024*1024);
        Wire.check(canaryPath!=null && canaryPath.length()<=512 && canaryPath.startsWith("/")
            && canaryPath.endsWith("/com.thekain.runtime.lab/files/isolated-owner-canary") && !canaryPath.contains("..") && canaryPath.indexOf(0)<0);
        Wire.check(sha256!=null && sha256.matches("[0-9a-f]{64}"));
    }
    @Override public void writeToParcel(Parcel p,int flags) {
        validate();request.writeToParcel(p,flags);p.writeInt(mode);p.writeLong(bytes);p.writeString(sha256);p.writeString(canaryPath);p.writeLong(guardDevice);p.writeLong(guardInode);
        resources.writeToParcel(p,flags);certificate.writeToParcel(p,flags);key.writeToParcel(p,flags);guard.writeToParcel(p,flags);
    }
    @Override public int describeContents() {return CONTENTS_FILE_DESCRIPTOR;}
    @Override public void close() {
        for(ParcelFileDescriptor fd:new ParcelFileDescriptor[]{resources,certificate,key,guard})if(fd!=null)try {fd.close();}catch(IOException ignored) { }
    }
    public static final Creator<IsolatedInput> CREATOR=new Creator<IsolatedInput>() {
        public IsolatedInput createFromParcel(Parcel p) {return new IsolatedInput(p);}
        public IsolatedInput[] newArray(int n) {return new IsolatedInput[n];}
    };
}
