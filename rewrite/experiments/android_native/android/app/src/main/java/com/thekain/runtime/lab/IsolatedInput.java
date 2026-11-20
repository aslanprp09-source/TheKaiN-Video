package com.thekain.runtime.lab;

import android.os.*;
import java.io.*;

/** Three read-only, unlinked regular files. Never a directory, code string or writable FD. */
public final class IsolatedInput implements Parcelable,AutoCloseable {
    static final int NORMAL=0,SECURITY=1,BLOCK_MAIN=2,FAKE_PROC=3,BAD_DIGEST=4,IPC_FLOOD=5,CHILD_FRAME=6,PLATFORM_CREDENTIAL=7;
    final RuntimeRequest request;
    final int mode;
    final ParcelFileDescriptor resources,certificate,key;
    final long bytes;
    final String sha256;
    IsolatedInput(RuntimeRequest request,int mode,ParcelFileDescriptor resources,
            ParcelFileDescriptor certificate,ParcelFileDescriptor key,long bytes,String sha256) {
        this.request=request;this.mode=mode;this.resources=resources;this.certificate=certificate;this.key=key;
        this.bytes=bytes;this.sha256=sha256;validate();
    }
    private IsolatedInput(Parcel p) {
        request=RuntimeRequest.CREATOR.createFromParcel(p);mode=p.readInt();bytes=p.readLong();
        sha256=Wire.string(p,64,false);
        ParcelFileDescriptor[] fds=readDescriptors(p);resources=fds[0];certificate=fds[1];key=fds[2];
        try {validate();}catch(RuntimeException e) {close();throw e;}
    }
    private static ParcelFileDescriptor[] readDescriptors(Parcel p) {
        ParcelFileDescriptor[] fds=new ParcelFileDescriptor[3];
        try {for(int i=0;i<3;i++)fds[i]=ParcelFileDescriptor.CREATOR.createFromParcel(p);return fds;}
        catch(RuntimeException e) {for(ParcelFileDescriptor fd:fds)if(fd!=null)try {fd.close();}catch(IOException ignored){}throw e;}
    }
    void validate() {
        Wire.check(request!=null);request.validate();Wire.check(mode>=NORMAL && mode<=PLATFORM_CREDENTIAL);
        Wire.check(resources!=null && certificate!=null && key!=null && bytes>0 && bytes<=160L*1024*1024);
        Wire.check(sha256!=null && sha256.matches("[0-9a-f]{64}"));
    }
    @Override public void writeToParcel(Parcel p,int flags) {
        validate();request.writeToParcel(p,flags);p.writeInt(mode);p.writeLong(bytes);p.writeString(sha256);
        resources.writeToParcel(p,flags);certificate.writeToParcel(p,flags);key.writeToParcel(p,flags);
    }
    @Override public int describeContents() {return CONTENTS_FILE_DESCRIPTOR;}
    @Override public void close() {
        for(ParcelFileDescriptor fd:new ParcelFileDescriptor[]{resources,certificate,key})if(fd!=null)try {fd.close();}catch(IOException ignored) { }
    }
    public static final Creator<IsolatedInput> CREATOR=new Creator<IsolatedInput>() {
        public IsolatedInput createFromParcel(Parcel p) {return new IsolatedInput(p);}
        public IsolatedInput[] newArray(int n) {return new IsolatedInput[n];}
    };
}
