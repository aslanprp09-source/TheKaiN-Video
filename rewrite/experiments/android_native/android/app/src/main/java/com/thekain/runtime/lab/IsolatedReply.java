package com.thekain.runtime.lab;

import android.os.*;

/** At most one 512KiB response, no headers, cookies, descriptors or arbitrary objects. */
public final class IsolatedReply implements Parcelable {
    final int error;
    final byte[] body;
    IsolatedReply(int error,byte[] body) {
        Wire.check(error>=0 && error<=RuntimeResult.OUT_OF_SCOPE);
        Wire.check(body==null || body.length<=ChildNetworkGate.MAX_RESPONSE);
        Wire.check(error==0 ? body!=null : body==null);
        this.error=error;this.body=body;
    }
    private IsolatedReply(Parcel p) {
        // Check length before allocation, not Parcel.createByteArray on attacker-controlled length.
        error=p.readInt();int n=p.readInt();Wire.check(n>=-1 && n<=ChildNetworkGate.MAX_RESPONSE);
        body=n<0?null:new byte[n];if(body!=null)p.readByteArray(body);
        Wire.check(error>=0 && error<=RuntimeResult.OUT_OF_SCOPE && (error==0?body!=null:body==null));
    }
    @Override public void writeToParcel(Parcel p,int flags) {
        p.writeInt(error);p.writeInt(body==null?-1:body.length);if(body!=null)p.writeByteArray(body);
    }
    @Override public int describeContents() {return 0;}
    public static final Creator<IsolatedReply> CREATOR=new Creator<IsolatedReply>() {
        public IsolatedReply createFromParcel(Parcel p) {return new IsolatedReply(p);}
        public IsolatedReply[] newArray(int n) {return new IsolatedReply[n];}
    };
}
