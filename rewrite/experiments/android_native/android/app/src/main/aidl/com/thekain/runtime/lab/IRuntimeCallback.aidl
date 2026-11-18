package com.thekain.runtime.lab;
import com.thekain.runtime.lab.RuntimeResult;
interface IRuntimeCallback {
    boolean ready(String id, String instance, long startTicks);
    void phase(String id, int phase);
    void limits(String id, long vmBefore, long addressSpaceLimit, long cpuLimit);
    void network(String id, int requests, long bytes);
    void completed(in RuntimeResult result);
}
