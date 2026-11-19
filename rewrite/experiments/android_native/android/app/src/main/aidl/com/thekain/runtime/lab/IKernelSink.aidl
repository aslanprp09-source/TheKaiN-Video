package com.thekain.runtime.lab;
import android.os.ParcelFileDescriptor;
/** Single-use descriptor capability; cannot configure or stop the monitor. */
interface IKernelSink {
    boolean attach(String capability, in ParcelFileDescriptor stat, in ParcelFileDescriptor status);
}
