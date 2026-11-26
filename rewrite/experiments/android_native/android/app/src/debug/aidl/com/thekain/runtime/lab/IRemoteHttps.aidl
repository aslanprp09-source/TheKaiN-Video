package com.thekain.runtime.lab;
import com.thekain.runtime.lab.BrokerHello;
import com.thekain.runtime.lab.BrokerConfig;
import com.thekain.runtime.lab.BrokerChunk;
import com.thekain.runtime.lab.IRemoteHttpsOwner;
interface IRemoteHttps {
    BrokerHello hello();
    oneway void configure(in BrokerConfig config, IRemoteHttpsOwner owner);
    BrokerChunk fetch(String capability, long sequence, String url, String method);
    BrokerChunk read(String capability, long sequence, int offset);
    oneway void retire(String capability);
}
