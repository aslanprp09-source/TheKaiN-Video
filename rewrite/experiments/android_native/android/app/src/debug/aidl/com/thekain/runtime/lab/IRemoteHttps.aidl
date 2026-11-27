package com.thekain.runtime.lab;
import com.thekain.runtime.lab.BrokerHello;
import com.thekain.runtime.lab.BrokerConfig;
import com.thekain.runtime.lab.BrokerChunk;
import com.thekain.runtime.lab.IRemoteHttpsOwner;
import com.thekain.runtime.lab.BrokerDownload;
import android.os.ParcelFileDescriptor;
interface IRemoteHttps {
    BrokerHello hello();
    // PID authentication requires a synchronous transaction: oneway callers have PID 0.
    // The owner invokes this only from its disposable-process handshake executor.
    void configure(in BrokerConfig config, IRemoteHttpsOwner owner);
    BrokerChunk fetch(String capability, long sequence, String url, String method);
    BrokerChunk read(String capability, long sequence, int offset);
    BrokerDownload download(String capability, String url, in ParcelFileDescriptor pipe);
    void retire(String capability);
}
