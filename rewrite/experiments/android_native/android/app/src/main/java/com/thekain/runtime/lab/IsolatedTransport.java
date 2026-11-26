package com.thekain.runtime.lab;

import android.content.Context;

/** Trusted owner configuration, never supplied by a child IPC request. */
public interface IsolatedTransport extends AutoCloseable {
    IsolatedReply reply(String url,String method) throws ChildNetworkGate.Failure;
    boolean terminated();
    default int failure() {return RuntimeResult.OK;}
    default org.json.JSONObject evidence() throws Exception {return null;}
    @Override void close();
    interface Counters {void update(int requests,long bytes);}
    interface Factory {
        IsolatedTransport create(Context context,RuntimeRequest request,Counters counters) throws Exception;
        default void validate(AnalysisMetadata analysis) throws Exception { }
    }
}
