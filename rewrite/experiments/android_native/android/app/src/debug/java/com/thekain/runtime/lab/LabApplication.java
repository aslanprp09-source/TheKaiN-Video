package com.thekain.runtime.lab;

import android.app.Application;

/** Debug companion only; worker Application startup never creates a session or fixture. */
public final class LabApplication extends Application {
    private LabSession session;
    synchronized LabSession session() {
        if(ProcessIdentity.inWorker())throw new IllegalStateException("NO_QA_OWNER_IN_WORKER");
        if(session==null)session=new LabSession(this);
        return session;
    }
    synchronized void resetForTest() throws Exception {
        if(session!=null) {session.close();session=null;}
    }
}
