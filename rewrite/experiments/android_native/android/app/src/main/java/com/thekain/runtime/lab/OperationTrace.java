package com.thekain.runtime.lab;

import android.os.SystemClock;
import org.json.*;
import java.util.*;

/** Bounded in-memory observations only. Never authorizes retirement or lease recovery. */
final class OperationTrace {
    static final int LIMIT=24;
    private final ArrayList<JSONObject> events=new ArrayList<>();
    private final HashSet<String> seen=new HashSet<>();
    private int dropped;
    synchronized void mark(String phase) {
        if(!Arrays.asList("SUBMITTED","STARTED","DEADLINE","RETIRING","CLEANUP","COMPLETED").contains(phase))
            throw new IllegalArgumentException("TRACE_PHASE");
        if(!seen.add(phase))return;
        try {
            if(events.size()==LIMIT) {events.remove(1);dropped++;}
            events.add(new JSONObject().put("phase",phase).put("elapsed_ms",SystemClock.elapsedRealtime())
                .put("wall_ms",System.currentTimeMillis()));
        }catch(JSONException impossible) {throw new IllegalStateException(impossible);}
    }
    synchronized JSONObject snapshot() throws JSONException {
        JSONArray copy=new JSONArray();for(JSONObject event:events)copy.put(new JSONObject(event.toString()));
        return new JSONObject().put("limit",LIMIT).put("dropped",dropped).put("events",copy);
    }
}
