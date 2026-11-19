package com.thekain.runtime.lab;
import com.thekain.runtime.lab.IKernelSink;
import com.thekain.runtime.lab.IResourceOwner;
/** Owner-only control; never passed to Python's process. */
interface IResourceObserver {
    IKernelSink prepare(String capability, IResourceOwner owner);
    boolean expectWorker(String capability, int pid, int uid);
}
