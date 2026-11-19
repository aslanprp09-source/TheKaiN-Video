package com.thekain.runtime.lab;

/** JNI/CPython is loaded only by the separate isolated UID. */
final class IsolatedChild {
    static {
        if(!IsolatedRuntimeService.inWorker())throw new IllegalStateException("ISOLATED_WORKER_REQUIRED");
        System.loadLibrary("python3.14");System.loadLibrary("thekain_lab");
    }
    static native long[] configureBudget(long headroom,int cpuSeconds);
    static native int sealedCopy(int source,long max);
    static native String call(String resources,String nativeDirectory,String request,IsolatedGate gate);
    private IsolatedChild() { }
}
