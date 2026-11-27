package com.thekain.runtime.lab;
import android.os.*;
/** A 32KiB maximum IPC chunk; offsets/sequence bind it to exactly one response. */
public final class BrokerChunk implements Parcelable {
    static final int CHUNK=32768,MAX_ERROR=15;
    final int error,status,total,offset,requests;final long sequence,payload,declaredBytes;final String finalUrl,contentType;final byte[] data;
    BrokerChunk(int error,long sequence,int status,String url,String type,long declared,int total,int offset,byte[] data,int requests,long payload) {
        this.error=error;this.sequence=sequence;this.status=status;finalUrl=url;contentType=type;declaredBytes=declared;
        this.total=total;this.offset=offset;this.data=data;this.requests=requests;this.payload=payload;validate();
    }
    static BrokerChunk failure(int error,long sequence,int requests,long payload) {return new BrokerChunk(error,sequence,0,null,null,-1,0,0,null,requests,payload);}
    private BrokerChunk(Parcel p) {
        error=p.readInt();sequence=p.readLong();status=p.readInt();finalUrl=BrokerConfig.string(p,2048,true);contentType=BrokerConfig.string(p,128,true);
        declaredBytes=p.readLong();total=p.readInt();offset=p.readInt();requests=p.readInt();payload=p.readLong();
        int n=p.readInt();Wire.check(n>=-1 && n<=CHUNK);data=n<0?null:new byte[n];if(data!=null)p.readByteArray(data);validate();
    }
    void validate() {
        Wire.check(error>=0 && error<=MAX_ERROR && sequence>0 && requests>=0 && requests<=12 && payload>=0 && payload<=2L*1024*1024+1);
        if(error!=0) {Wire.check(status==0 && finalUrl==null && contentType==null && data==null && total==0 && offset==0);return;}
        Wire.check(status==200 && declaredBytes>=-1 && total>=0 && total<=512*1024 && offset>=0 && offset<=total
            && data!=null && data.length<=CHUNK && data.length==Math.min(CHUNK,total-offset));
        RuntimeRequest.validateSourceUrl(finalUrl);Wire.check(contentType==null || contentType.length()<=128);
    }
    public void writeToParcel(Parcel p,int flags) {
        validate();p.writeInt(error);p.writeLong(sequence);p.writeInt(status);p.writeString(finalUrl);p.writeString(contentType);p.writeLong(declaredBytes);
        p.writeInt(total);p.writeInt(offset);p.writeInt(requests);p.writeLong(payload);p.writeInt(data==null?-1:data.length);if(data!=null)p.writeByteArray(data);
    }
    public int describeContents() {return 0;}
    public static final Creator<BrokerChunk> CREATOR=new Creator<BrokerChunk>() {
        public BrokerChunk createFromParcel(Parcel p) {return new BrokerChunk(p);}
        public BrokerChunk[] newArray(int n) {return new BrokerChunk[n];}
    };
}
