package com.thekain.runtime.lab;
interface IRemoteHttpsOwner {
    boolean ready(String capability, int pid, long startTicks);
    void phase(String capability, int phase);
}
