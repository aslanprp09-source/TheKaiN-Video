package com.thekain.runtime.lab;
import android.os.*;
import org.json.*;
import java.util.*;

/** Small typed extractor declarations. No download authorization, files, raw JSON or executable payload. */
public final class AnalysisMetadata implements Parcelable {
    public final String title,host;
    private final Format[] formats;
    public List<Format> formats() {return Collections.unmodifiableList(Arrays.asList(formats.clone()));}
    public static final class Format {
        public final String id,url,container,videoCodec,audioCodec;
        public final long bytes;public final int width,height;public final double fps;
        Format(String id,String url,String container,String video,String audio,long bytes,int width,int height,double fps) {
            this.id=id;this.url=url;this.container=container;videoCodec=video;audioCodec=audio;this.bytes=bytes;this.width=width;this.height=height;this.fps=fps;validate();
        }
        private Format(Parcel p) {this(Wire.string(p,64,false),Wire.string(p,2048,false),Wire.string(p,8,false),Wire.string(p,64,true),Wire.string(p,64,true),p.readLong(),p.readInt(),p.readInt(),p.readDouble());}
        private void validate() {
            Wire.check(id!=null && id.matches("[A-Za-z0-9_.:+-]{1,64}") && "mp4".equals(container));RuntimeRequest.validateSourceUrl(url);
            for(String value:new String[]{videoCodec,audioCodec})Wire.check(value==null || value.matches("[A-Za-z0-9_.:+-]{1,64}"));
            Wire.check((bytes==-1 || bytes>0) && width>=0 && width<=16384 && height>=0 && height<=16384 && Double.isFinite(fps) && fps>=0 && fps<=1000);
        }
        void write(Parcel p) {validate();p.writeString(id);p.writeString(url);p.writeString(container);p.writeString(videoCodec);p.writeString(audioCodec);p.writeLong(bytes);p.writeInt(width);p.writeInt(height);p.writeDouble(fps);}
        @Override public String toString() {return "Format(container="+container+")";}
    }
    AnalysisMetadata(String title,String host,Format[] formats) {this.title=title;this.host=host;this.formats=formats.clone();validate();}
    static AnalysisMetadata decode(JSONObject j) throws Exception {
        JSONArray values=j.getJSONArray("formats");Wire.check(values.length()>0 && values.length()<=8);Format[] formats=new Format[values.length()];
        for(int i=0;i<formats.length;i++) {
            JSONObject f=values.getJSONObject(i);
            Wire.check(f.isNull("fps") || f.get("fps") instanceof Number);
            formats[i]=new Format(f.getString("id"),f.getString("url"),f.getString("container"),nullable(f,"video_codec"),nullable(f,"audio_codec"),
                integer(f,"bytes",-1),Math.toIntExact(integer(f,"width",0)),Math.toIntExact(integer(f,"height",0)),f.isNull("fps")?0:f.getDouble("fps"));
        }
        return new AnalysisMetadata(j.getString("title"),j.getString("host"),formats);
    }
    private static String nullable(JSONObject j,String key) throws Exception {return j.isNull(key)?null:j.getString(key);}
    private static long integer(JSONObject j,String key,long unknown) throws Exception {
        Object value=j.opt(key);if(value==null || value==JSONObject.NULL)return unknown;
        Wire.check(value instanceof Integer || value instanceof Long);return ((Number)value).longValue();
    }
    private AnalysisMetadata(Parcel p) {
        title=Wire.string(p,320,false);host=Wire.string(p,253,false);int n=p.readInt();Wire.check(n>0 && n<=8);formats=new Format[n];
        for(int i=0;i<n;i++)formats[i]=new Format(p);validate();
    }
    void validate() {
        Wire.check(title!=null && title.length()>0 && title.length()<=320 && title.chars().noneMatch(c->c<32 || c==127));
        Wire.check(host!=null && host.length()<=253 && host.matches("[a-z0-9.-]+") && formats.length>0 && formats.length<=8);
        int characters=title.length()+host.length();Set<String> ids=new HashSet<>();
        for(Format f:formats) {Wire.check(f!=null && ids.add(f.id));f.validate();characters+=f.id.length()+f.url.length()+f.container.length()
            +(f.videoCodec==null?0:f.videoCodec.length())+(f.audioCodec==null?0:f.audioCodec.length());}
        Wire.check(characters<=4096); // Entire RuntimeResult stays below Wire's unchanged 16KiB parcel bound.
    }
    public void writeToParcel(Parcel p,int flags) {validate();p.writeString(title);p.writeString(host);p.writeInt(formats.length);for(Format f:formats)f.write(p);}
    public int describeContents() {return 0;}
    @Override public String toString() {return "AnalysisMetadata(formats="+formats.length+")";}
    public static final Creator<AnalysisMetadata> CREATOR=new Creator<AnalysisMetadata>() {
        public AnalysisMetadata createFromParcel(Parcel p) {return new AnalysisMetadata(p);}
        public AnalysisMetadata[] newArray(int n) {return new AnalysisMetadata[n];}
    };
}
