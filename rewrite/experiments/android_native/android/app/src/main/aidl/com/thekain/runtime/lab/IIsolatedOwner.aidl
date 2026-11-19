package com.thekain.runtime.lab;
import com.thekain.runtime.lab.RuntimeResult;
import com.thekain.runtime.lab.IsolatedReply;
interface IIsolatedOwner {
    boolean ready(String capability, String instance);
    void phase(String capability);
    void limits(String capability, long before, long address, long cpu);
    IsolatedReply fetch(String capability, String url, String method);
    void completed(String capability, in RuntimeResult result, int securityDenials);
}
