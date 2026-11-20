package com.thekain.runtime.lab;

import java.io.IOException;

/** Owner-only kernel file-lock operations; never libpython. */
final class LeaseNative {
    static {
        try {
            String name=ProcessIdentity.read("/proc/self/cmdline",512).split("\u0000",2)[0];
            int uid=android.os.Process.myUid()%100000;
            if(!name.equals("com.thekain.runtime.lab") || uid<10000 || uid>=90000)
                throw new IllegalStateException("LEASE_OWNER_REQUIRED");
        }catch(IOException e) {throw new IllegalStateException("LEASE_OWNER_IDENTITY",e);}
        System.loadLibrary("thekain_lease");
    }
    static native int create(String path) throws IOException;
    static native int acquire(String path,long device,long inode) throws IOException;
    private LeaseNative() { }
}
