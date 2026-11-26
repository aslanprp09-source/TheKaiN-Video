package com.thekain.runtime.lab;

import android.os.*;

/** At most one 512KiB response, no headers, cookies, descriptors or arbitrary objects. */
public final class IsolatedReply implements Parcelable {
    final int error;
    final byte[] body;
    final int status;final String finalUrl,contentType;final long declaredBytes;
    IsolatedReply(int error,byte[] body) {
        this(error,body,0,null,null,-1);
    }
    IsolatedReply(int error,byte[] body,int status,String finalUrl,String contentType,long declaredBytes) {
        Wire.check(error>=0 && error<=RuntimeResult.CPU_LIMIT);
        Wire.check(body==null || body.length<=ChildNetworkGate.MAX_RESPONSE);
        Wire.check(error==0 ? body!=null : body==null);
        this.error=error;this.body=body;
        this.status=status;this.finalUrl=finalUrl;this.contentType=contentType;this.declaredBytes=declaredBytes;metadata();
    }
    private void metadata() {
        Wire.check(declaredBytes>=-1 && (status==0?finalUrl==null && contentType==null:status==200 && error==0));
        if(status!=0) {RuntimeRequest.validateSourceUrl(finalUrl);Wire.check(contentType==null || contentType.length()<=128);}
    }
    private IsolatedReply(Parcel p) {
        // Check length before allocation, not Parcel.createByteArray on attacker-controlled length.
        error=p.readInt();int n=p.readInt();Wire.check(n>=-1 && n<=ChildNetworkGate.MAX_RESPONSE);
        body=n<0?null:new byte[n];if(body!=null)p.readByteArray(body);
        Wire.check(error>=0 && error<=RuntimeResult.CPU_LIMIT && (error==0?body!=null:body==null));
        status=p.readInt();finalUrl=Wire.string(p,2048,true);contentType=Wire.string(p,128,true);declaredBytes=p.readLong();metadata();
    }
    @Override public void writeToParcel(Parcel p,int flags) {
        p.writeInt(error);p.writeInt(body==null?-1:body.length);if(body!=null)p.writeByteArray(body);
        p.writeInt(status);p.writeString(finalUrl);p.writeString(contentType);p.writeLong(declaredBytes);
    }
    @Override public int describeContents() {return 0;}
    public static final Creator<IsolatedReply> CREATOR=new Creator<IsolatedReply>() {
        public IsolatedReply createFromParcel(Parcel p) {return new IsolatedReply(p);}
        public IsolatedReply[] newArray(int n) {return new IsolatedReply[n];}
    };
}
