package com.thekain.runtime.lab;
import android.os.*;
public final class BrokerHello implements Parcelable {
    final int pid,uid;final long start;final String instance;
    BrokerHello(int pid,int uid,long start,String instance) {this.pid=pid;this.uid=uid;this.start=start;this.instance=instance;validate();}
    private BrokerHello(Parcel p) {this(p.readInt(),p.readInt(),p.readLong(),Wire.string(p,36,false));}
    private void validate() {Wire.check(pid>0 && uid>0 && start>0 && instance!=null && instance.matches("[0-9a-f-]{36}"));}
    public void writeToParcel(Parcel p,int flags) {validate();p.writeInt(pid);p.writeInt(uid);p.writeLong(start);p.writeString(instance);}
    public int describeContents() {return 0;}
    public static final Creator<BrokerHello> CREATOR=new Creator<BrokerHello>() {
        public BrokerHello createFromParcel(Parcel p) {return new BrokerHello(p);}
        public BrokerHello[] newArray(int n) {return new BrokerHello[n];}
    };
}
