package com.thekain.runtime.lab;
import java.io.IOException;
/** libc-only FD flag query; no Python or owner file-lock API is exposed to this Service. */
final class BrokerGuardFlags {
    static {
        try {
            String name=ProcessIdentity.read("/proc/self/cmdline",512).split("\u0000",2)[0];int uid=android.os.Process.myUid()%100000;
            if((!name.equals("com.thekain.runtime.lab:https_broker") && !name.equals("com.thekain.video.rebuild:https_broker")) || uid<10000 || uid>=90000)
                throw new IllegalStateException("BROKER_PROCESS_REQUIRED");
        }catch(IOException e) {throw new IllegalStateException("BROKER_PROCESS_IDENTITY");}
        System.loadLibrary("thekain_lease");
    }
    static native int flags(int fd) throws IOException;
    private BrokerGuardFlags() { }
}
