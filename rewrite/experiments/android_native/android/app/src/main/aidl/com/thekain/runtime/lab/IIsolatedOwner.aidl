package com.thekain.runtime.lab;
import com.thekain.runtime.lab.RuntimeResult;
import com.thekain.runtime.lab.IsolatedReply;
interface IIsolatedOwner {
    boolean ready(String capability, String instance, int childPid);
    void phase(String capability);
    boolean limits(String capability, long before, long address, long cpu, int childProtection);
    IsolatedReply fetch(String capability, String url, String method);
    void credentialDenied(String capability, int proof);
    void completed(String capability, in RuntimeResult result, int securityDenials);
}
