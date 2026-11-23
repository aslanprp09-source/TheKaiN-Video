package com.thekain.runtime.lab;

import android.os.SystemClock;
import org.json.*;
import java.util.UUID;

/** QA evidence only: immutable old outcomes are separate from the current attempt. */
final class QaAttemptJournal {
    static final int ATTEMPT_LIMIT=16,UNFINISHED_LIMIT=4;
    private final JSONObject state;
    QaAttemptJournal(JSONObject state) {this.state=state;}
    private void append(String key,JSONObject value,int limit) throws JSONException {
        JSONArray old=state.optJSONArray(key);
        if(old!=null && old.length()>=limit)state.put(key+"_evicted",state.optInt(key+"_evicted")+1);
        LabQaStore.append(state,key,value,limit);
    }
    private JSONObject current() throws JSONException {
        String id=state.optString("current_attempt_id");JSONArray rows=state.optJSONArray("attempts");
        if(rows!=null)for(int i=rows.length()-1;i>=0;i--)if(rows.getJSONObject(i).getString("id").equals(id))return rows.getJSONObject(i);
        return null;
    }
    void restart() throws JSONException {
        if(!state.has("attempt_schema")) {
            state.put("attempt_schema",1);
            if(state.has("qa_failure_class"))append("attempts",new JSONObject()
                .put("id",UUID.randomUUID().toString()).put("kind","LEGACY")
                .put("status","FAILED").put("failure_class",state.getString("qa_failure_class"))
                .put("timestamps_unavailable",true).put("historical",true),ATTEMPT_LIMIT);
        }
        JSONObject attempt=current();
        if(attempt!=null && attempt.getString("status").equals("RUNNING")) {
            attempt.put("status","INTERRUPTED").put("interruption_observed_wall_ms",System.currentTimeMillis())
                .put("interruption_observed_elapsed_ms",SystemClock.elapsedRealtime());
        }
        if(state.has("pending")) {
            // Preserve the actual operation/phase snapshot; restart is not completion.
            append("unfinished_operations",state.getJSONObject("pending"),UNFINISHED_LIMIT);
            state.put("interrupted_previous_session",true);state.remove("pending");
        }
    }
    String begin(String kind) throws JSONException {
        JSONObject previous=current();
        if(previous!=null && previous.getString("status").equals("RUNNING"))throw new IllegalStateException("QA_ATTEMPT_ACTIVE");
        if(state.has("pending")) {
            append("unfinished_operations",state.getJSONObject("pending"),UNFINISHED_LIMIT);state.remove("pending");
        }
        String id=UUID.randomUUID().toString();
        JSONObject attempt=new JSONObject().put("id",id).put("kind",kind).put("status","RUNNING")
            .put("started_elapsed_ms",SystemClock.elapsedRealtime()).put("started_wall_ms",System.currentTimeMillis())
            .put("started_scenarios",0).put("completed_scenarios",0);
        append("attempts",attempt,ATTEMPT_LIMIT);state.put("current_attempt_id",id);
        if(kind.equals("SUITE"))state.put("last_suite_attempt_id",id);
        // Old errors remain in their attempt/unfinished records. Only this new
        // attempt's current failure is clear; missing suite coverage still fails.
        state.remove("qa_failure_class");state.remove("recovery_failure_class");return id;
    }
    JSONObject scenario(String operationId,String name) throws JSONException {
        JSONObject attempt=current();if(attempt==null)throw new IllegalStateException("QA_NO_ATTEMPT");
        if(state.has("pending"))throw new IllegalStateException("QA_PENDING_NOT_ARCHIVED");
        int index=attempt.getInt("started_scenarios")+1;attempt.put("started_scenarios",index);
        JSONObject value=new JSONObject().put("id",operationId).put("attempt_id",attempt.getString("id"))
            .put("scenario_id",UUID.randomUUID().toString()).put("scenario_name",name).put("scenario_index",index)
            .put("started_elapsed_ms",SystemClock.elapsedRealtime()).put("started_wall_ms",System.currentTimeMillis());
        state.put("pending",value);return value;
    }
    void scenarioCompleted() throws JSONException {
        JSONObject attempt=current();attempt.put("completed_scenarios",attempt.getInt("completed_scenarios")+1);
        state.remove("pending");
    }
    void completed() throws JSONException {
        JSONObject attempt=current();if(state.has("pending") || attempt.getInt("started_scenarios")!=attempt.getInt("completed_scenarios"))
            throw new IllegalStateException("QA_INCOMPLETE_SCENARIO");
        attempt.put("status","COMPLETED").put("finished_elapsed_ms",SystemClock.elapsedRealtime())
            .put("finished_wall_ms",System.currentTimeMillis());
    }
    void failed(String error) throws JSONException {
        JSONObject attempt=current();
        if(attempt!=null && attempt.getString("status").equals("RUNNING")) {
            attempt.put("status","FAILED").put("failure_class",error)
                .put("finished_elapsed_ms",SystemClock.elapsedRealtime()).put("finished_wall_ms",System.currentTimeMillis());
            if(state.has("pending"))attempt.put("failed_scenario_id",state.getJSONObject("pending").getString("scenario_id"));
        }
        state.put("qa_failure_class",error);
        // Keep pending for timeout export and preserve another copy on restart.
    }
}
