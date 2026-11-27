package com.thekain.runtime.lab;

import android.os.Parcel;
import android.os.Parcelable;
import org.json.JSONObject;

/** Bounded typed result: validated candidate URLs only; no raw Python JSON, file paths or exception messages. */
public final class RuntimeResult implements Parcelable {
    public static final int OK=0,CANCELED=1,TIMEOUT=2,BUSY=3,WORKER_DIED=4,IPC_ERROR=5,
        INVALID_REQUEST=6,MEMORY_LIMIT=7,TLS_ERROR=8,REQUEST_LIMIT=9,RESPONSE_LIMIT=10,
        OUTPUT_LIMIT=11,NATIVE_FAILED=12,CLEANUP_FAILED=13,OUT_OF_SCOPE=14,CPU_LIMIT=15;
    public final String id,python,openssl,platform,videoCodec,audioCodec,machine;
    public static final int REASON_NONE=0,TRACKS_UNKNOWN=1,UNSUPPORTED=2;
    public final int error,pages,mimalloc,formats,reason,pointerBits;
    public final boolean installedExtensions,externalExecBlocked,eligible,downloadEnabled,opensslHandshake;
    public final long pythonCalls;
    public final AnalysisMetadata analysis;

    RuntimeResult(String id,int error) { this(id,error,null,null,null,null,null,0,0,0,false,false,false,false,false,0,REASON_NONE,null,0,null); }
    private RuntimeResult(String id,int error,String python,String openssl,String platform,String video,String audio,
            int pages,int mimalloc,int formats,boolean installed,boolean blocked,boolean eligible,boolean download,
            boolean handshake,long calls,int reason,String machine,int pointerBits,AnalysisMetadata analysis) {
        this.id=id;this.error=error;this.python=python;this.openssl=openssl;this.platform=platform;
        videoCodec=video;audioCodec=audio;this.pages=pages;this.mimalloc=mimalloc;this.formats=formats;
        installedExtensions=installed;externalExecBlocked=blocked;this.eligible=eligible;downloadEnabled=download;
        opensslHandshake=handshake;pythonCalls=calls;this.reason=reason;this.machine=machine;this.pointerBits=pointerBits;this.analysis=analysis;validate();
    }
    static int error(String code) {
        switch(code) {
            case "CPU_LIMIT":return CPU_LIMIT;
            case "TLS_ERROR":return TLS_ERROR;
            case "TIMEOUT":return TIMEOUT;
            case "REQUEST_LIMIT":return REQUEST_LIMIT;
            case "RESPONSE_LIMIT":return RESPONSE_LIMIT;
            case "OUTPUT_LIMIT":return OUTPUT_LIMIT;
            case "MEMORY_LIMIT":return MEMORY_LIMIT;
            case "OUT_OF_SCOPE_URL":return OUT_OF_SCOPE;
            default:return NATIVE_FAILED;
        }
    }
    static RuntimeResult decode(String id,String raw) throws Exception {
        Wire.check(raw.length()<=65536);
        JSONObject j=new JSONObject(raw);
        if(!"PASS".equals(j.getString("status"))) return new RuntimeResult(id,error(j.optString("error_code")));
        return new RuntimeResult(id,OK,j.has("python")?j.getJSONArray("python").toString():null,
            j.optString("openssl",null),j.optString("platform",null),nullable(j,"video_codec"),nullable(j,"audio_codec"),
            j.optInt("page_size"),j.optInt("mimalloc"),j.optInt("formats"),j.optBoolean("installed_native_extensions"),
            j.optBoolean("external_exec_blocked"),j.optBoolean("eligible"),j.optBoolean("download_enabled"),
            j.optBoolean("openssl_handshake"),j.optLong("python_calls"),
            "TRACKS_UNKNOWN".equals(j.optString("reason"))?TRACKS_UNKNOWN:"INTEGRATION_DISABLED".equals(j.optString("reason"))?UNSUPPORTED:REASON_NONE,
            j.optString("machine",null),j.optInt("pointer_bits"),j.has("analysis")?AnalysisMetadata.decode(j.getJSONObject("analysis")):null);
    }
    private static String nullable(JSONObject j,String key) { return j.isNull(key)?null:j.optString(key,null); }
    private RuntimeResult(Parcel in) {
        this(Wire.string(in,64,false),in.readInt(),Wire.string(in,64,true),Wire.string(in,128,true),
            Wire.string(in,32,true),Wire.string(in,128,true),Wire.string(in,128,true),in.readInt(),in.readInt(),
            in.readInt(),Wire.bool(in),Wire.bool(in),Wire.bool(in),Wire.bool(in),Wire.bool(in),in.readLong(),in.readInt(),
            Wire.string(in,32,true),in.readInt(),Wire.bool(in)?AnalysisMetadata.CREATOR.createFromParcel(in):null);
    }
    void validate() {
        Wire.check(id!=null && id.length()<=64 && error>=OK && error<=CPU_LIMIT);
        for(String s:new String[]{python,openssl,platform,videoCodec,audioCodec}) Wire.check(s==null || s.length()<=128);
        Wire.check(formats>=0 && formats<=128 && pages>=0 && pages<=65536 && pythonCalls>=0);
        Wire.check(reason>=REASON_NONE && reason<=UNSUPPORTED);
        Wire.check(machine==null || machine.matches("[A-Za-z0-9_]{1,32}"));
        Wire.check(pointerBits==0 || pointerBits==64);
        Wire.check(!downloadEnabled); // Lab never authorizes media transfer.
        if(analysis!=null) {analysis.validate();Wire.check(error==OK && formats==analysis.formats().size());}
    }
    @Override public void writeToParcel(Parcel out,int flags) {
        validate();out.writeString(id);out.writeInt(error);out.writeString(python);out.writeString(openssl);
        out.writeString(platform);out.writeString(videoCodec);out.writeString(audioCodec);out.writeInt(pages);
        out.writeInt(mimalloc);out.writeInt(formats);out.writeInt(installedExtensions?1:0);
        out.writeInt(externalExecBlocked?1:0);out.writeInt(eligible?1:0);out.writeInt(downloadEnabled?1:0);
        out.writeInt(opensslHandshake?1:0);out.writeLong(pythonCalls);out.writeInt(reason);
        out.writeString(machine);out.writeInt(pointerBits);
        out.writeInt(analysis==null?0:1);if(analysis!=null)analysis.writeToParcel(out,flags);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<RuntimeResult> CREATOR=new Creator<RuntimeResult>() {
        public RuntimeResult createFromParcel(Parcel in) { return new RuntimeResult(in); }
        public RuntimeResult[] newArray(int size) { return new RuntimeResult[size]; }
    };
}
