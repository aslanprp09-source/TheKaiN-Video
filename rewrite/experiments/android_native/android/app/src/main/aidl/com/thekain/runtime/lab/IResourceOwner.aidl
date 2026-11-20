package com.thekain.runtime.lab;
/** Only the authenticated Java observer may report these kernel measurements. */
interface IResourceOwner {
    boolean ready(String capability, long startTicks, boolean nonDumpable);
    boolean sample(String capability, long sequence, long measuredAt, int workerPid, int workerUid,
        long startTicks, long cpuTicks, long rssBytes, long virtualBytes, boolean dead,
        long observerRssBytes, long observerCpuTicks, boolean pythonLoaded, int childProtection, int exitSignal, int exitCode);
    void idle(String capability);
    void failed(String capability);
}
