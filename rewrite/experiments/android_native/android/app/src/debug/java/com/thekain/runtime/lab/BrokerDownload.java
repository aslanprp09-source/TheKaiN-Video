package com.thekain.runtime.lab;
import android.os.*;
/** Final transfer receipt; bytes travel only through a bounded kernel pipe. No file paths or headers. */
public final class BrokerDownload implements Parcelable {
    public static final long MAX_BYTES=512L*1024*1024;
    public final int error,httpStatus;
    public final long bytes,declaredBytes;
    public final String finalUrl,contentType;
    BrokerDownload(int error,int status,long bytes,long declared,String url,String type) {
        this.error=error;httpStatus=status;this.bytes=bytes;declaredBytes=declared;finalUrl=url;contentType=type;validate();
    }
    static BrokerDownload failure(int error,int status) {return new BrokerDownload(error,status,0,-1,null,null);}
    private BrokerDownload(Parcel p) {this(p.readInt(),p.readInt(),p.readLong(),p.readLong(),BrokerConfig.string(p,2048,true),BrokerConfig.string(p,128,true));}
    void validate() {
        Wire.check(error>=0 && error<=15 && httpStatus>=0 && httpStatus<=599);
        if(error!=0) {Wire.check(bytes==0 && declaredBytes==-1 && finalUrl==null && contentType==null);return;}
        Wire.check(httpStatus==200 && bytes>0 && bytes<=MAX_BYTES && bytes==declaredBytes);
        RuntimeRequest.validateSourceUrl(finalUrl);
        Wire.check(contentType==null || contentType.length()<=128 && contentType.chars().allMatch(c->c>=32 && c<=126));
    }
    public void writeToParcel(Parcel p,int flags) {validate();p.writeInt(error);p.writeInt(httpStatus);p.writeLong(bytes);p.writeLong(declaredBytes);p.writeString(finalUrl);p.writeString(contentType);}
    public int describeContents() {return 0;}
    public static final Creator<BrokerDownload> CREATOR=new Creator<BrokerDownload>() {
        public BrokerDownload createFromParcel(Parcel p) {return new BrokerDownload(p);}
        public BrokerDownload[] newArray(int n) {return new BrokerDownload[n];}
    };
}
