package com.thekain.runtime.lab;

/** libc-only JNI; never links libpython or executes Python inside ART. */
final class SupervisorNative {
    static {
        if(!IsolatedRuntimeService.inWorker())throw new IllegalStateException("ISOLATED_SUPERVISOR_REQUIRED");
        System.loadLibrary("thekain_supervisor");
    }
    static native boolean protect();
    static native int flags(int fd);
    static native int seal(int source,long max);
    static native int[] spawn(String installedDirectory,int zip,int cert,int key,int guard);
    static native void kill(int pid);
    static native boolean reap(int pid);
    static native int dumpable();
    static native int exitSignal(int pid);
    static native int exitCode(int pid);
    private SupervisorNative() { }
}
