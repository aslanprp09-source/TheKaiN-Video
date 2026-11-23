package com.thekain.runtime.lab;

import android.os.SystemClock;
import java.util.concurrent.*;

/** Observational outer wait. It never changes/cancels the Runtime request or manufactures an outcome. */
final class QaWait {
    interface Observer {void capture(String status,CompletableFuture<?> future,long until) throws Exception;}
    private static TimeoutException exceeded(CompletableFuture<?> future,long until,Observer observer) {
        TimeoutException timeout=new TimeoutException();
        try {observer.capture("WAIT_EXCEEDED",future,until);}
        catch(Exception recordingFailure) {timeout.addSuppressed(recordingFailure);}
        return timeout;
    }
    static <T> T await(CompletableFuture<T> future,int seconds,Observer observer) throws Exception {
        long until=SystemClock.elapsedRealtime()+seconds*1000L,lastSaved=0;
        while(true) {
            long now=SystemClock.elapsedRealtime(),remaining=until-now;
            if(remaining<=0)throw exceeded(future,until,observer);
            if(now-lastSaved>=2000) {observer.capture("WAITING",future,until);lastSaved=now;}
            remaining=until-SystemClock.elapsedRealtime();
            if(remaining<=0)throw exceeded(future,until,observer);
            T value;
            try {value=future.get(Math.min(250,remaining),TimeUnit.MILLISECONDS);}
            catch(TimeoutException notYet) {continue;}
            if(SystemClock.elapsedRealtime()>=until)throw exceeded(future,until,observer);
            observer.capture("RETURNED",future,until);return value;
        }
    }
}
