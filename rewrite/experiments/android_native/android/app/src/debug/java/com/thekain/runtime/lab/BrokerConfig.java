package com.thekain.runtime.lab;
import android.os.*;
import java.io.*;
import java.util.*;
/** Only an authenticated owner may grant origins/fixture CA. No Bundles, Python or writable resources. */
public final class BrokerConfig implements Parcelable,AutoCloseable {
    final String id,fixture;final String[] origins;final long deadline,device,inode;
    final long rssLimit,cpuMs;final int fault;final byte[] ca;final ParcelFileDescriptor guard;
    BrokerConfig(String id,long deadline,String[] origins,String fixture,byte[] ca,int fault,long rss,long cpu,IsolatedLease.Guard guard) {
        this.id=id;this.deadline=deadline;this.origins=origins.clone();this.fixture=fixture;this.ca=ca==null?null:ca.clone();
        this.fault=fault;rssLimit=rss;cpuMs=cpu;this.guard=guard.fd;device=guard.device;inode=guard.inode;validate();
    }
    static String string(Parcel p,int max,boolean nullable) {
        Wire.check(p.dataAvail()<=49152);String value=p.readString();Wire.check(value==null?nullable:value.length()<=max);return value;
    }
    private BrokerConfig(Parcel p) {
        id=string(p,36,false);deadline=p.readLong();int n=p.readInt();Wire.check(n>0 && n<=8);origins=new String[n];
        for(int i=0;i<n;i++)origins[i]=string(p,280,false);fixture=string(p,280,true);
        int length=p.readInt();Wire.check(length>=-1 && length<=16384);ca=length<0?null:new byte[length];if(ca!=null)p.readByteArray(ca);
        fault=p.readInt();rssLimit=p.readLong();cpuMs=p.readLong();device=p.readLong();inode=p.readLong();guard=ParcelFileDescriptor.CREATOR.createFromParcel(p);
        try {validate();}catch(RuntimeException e) {close();throw e;}
    }
    void validate() {
        Wire.check(id!=null && id.matches("[0-9a-f-]{36}") && deadline>0 && origins.length>0 && origins.length<=8);
        for(String origin:origins)Wire.check(origin!=null && origin.length()<=280);
        Wire.check(fault>=0 && fault<=3 && rssLimit>=16L*1024*1024 && rssLimit<=RuntimeRequest.MAX_RSS && cpuMs>=100 && cpuMs<=10000);
        Wire.check(fixture==null?ca==null:fixture.length()<=280 && ca!=null && ca.length>0 && ca.length<=16384);
        Wire.check(guard!=null && device>=0 && inode>0);
    }
    public void writeToParcel(Parcel p,int flags) {
        validate();p.writeString(id);p.writeLong(deadline);p.writeInt(origins.length);for(String origin:origins)p.writeString(origin);
        p.writeString(fixture);p.writeInt(ca==null?-1:ca.length);if(ca!=null)p.writeByteArray(ca);
        p.writeInt(fault);p.writeLong(rssLimit);p.writeLong(cpuMs);p.writeLong(device);p.writeLong(inode);guard.writeToParcel(p,flags);
    }
    public int describeContents() {return CONTENTS_FILE_DESCRIPTOR;}
    public void close() {try {guard.close();}catch(IOException ignored){}}
    public static final Creator<BrokerConfig> CREATOR=new Creator<BrokerConfig>() {
        public BrokerConfig createFromParcel(Parcel p) {return new BrokerConfig(p);}
        public BrokerConfig[] newArray(int n) {return new BrokerConfig[n];}
    };
}
