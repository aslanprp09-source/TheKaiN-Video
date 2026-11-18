package com.thekain.runtime.lab;

import android.os.Parcel;
import android.os.Parcelable;
import java.net.URI;

/** The broker creates IDs/deadlines; callers choose only these fixed lab operations. */
public final class RuntimeRequest implements Parcelable {
    public static final int PROBE=1, ANALYZE=2, BLOCK_EXEC=3, HANG=4, CRASH=5,
        MEMORY=6, BAD_REPLY=7, OUTPUT_FLOOD=8, REQUEST_FLOOD=9, DIRECT_SOCKET=10, BYTE_FLOOD=11;
    public static final int CA_OWN=1, CA_WRONG=2;
    public static final long MAX_RSS=384L*1024*1024, MAX_HEADROOM=256L*1024*1024;
    public final String id, url;
    public final int operation, ca;
    public final long deadline, rssLimit, virtualHeadroom;

    RuntimeRequest(String id,int operation,String url,int ca,long deadline,long rss,long headroom) {
        this.id=id;this.operation=operation;this.url=url;this.ca=ca;this.deadline=deadline;
        rssLimit=rss;virtualHeadroom=headroom;validate();
    }
    private RuntimeRequest(Parcel in) {
        id=Wire.string(in,64,false);operation=in.readInt();url=Wire.string(in,2048,true);
        ca=in.readInt();deadline=in.readLong();rssLimit=in.readLong();virtualHeadroom=in.readLong();validate();
    }
    void validate() {
        Wire.check(id!=null && id.matches("[0-9a-f-]{36}") && operation>=PROBE && operation<=BYTE_FLOOD);
        Wire.check(ca==CA_OWN || ca==CA_WRONG);
        Wire.check(deadline>0 && rssLimit>=64L*1024*1024 && rssLimit<=MAX_RSS);
        Wire.check(virtualHeadroom>=64L*1024*1024 && virtualHeadroom<=MAX_HEADROOM);
        if(operation==ANALYZE || operation==REQUEST_FLOOD || operation==BYTE_FLOOD) validateUrl(url);
        else Wire.check(url==null);
    }
    static void validateUrl(String url) {
        Wire.check(url!=null && url.length()<=2048 && url.chars().allMatch(c->c>=33 && c<127));
        try {
            URI uri=new URI(url);
            Wire.check("https".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                && uri.getPort()>0 && uri.getPort()<=65535 && uri.getRawUserInfo()==null && uri.getRawFragment()==null);
        } catch(java.net.URISyntaxException e) { Wire.check(false); }
    }
    @Override public void writeToParcel(Parcel out,int flags) {
        validate();out.writeString(id);out.writeInt(operation);out.writeString(url);out.writeInt(ca);
        out.writeLong(deadline);out.writeLong(rssLimit);out.writeLong(virtualHeadroom);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<RuntimeRequest> CREATOR=new Creator<RuntimeRequest>() {
        public RuntimeRequest createFromParcel(Parcel in) { return new RuntimeRequest(in); }
        public RuntimeRequest[] newArray(int size) { return new RuntimeRequest[size]; }
    };
}
