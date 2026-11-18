package com.thekain.runtime.lab;

import android.os.BadParcelableException;
import android.os.Parcel;

/** Fixed schema, bounded values, no Bundles, arbitrary code or file descriptors. */
final class Wire {
    static void check(boolean valid) { if (!valid) throw new BadParcelableException("LAB_IPC_SCHEMA"); }
    static String string(Parcel in, int max, boolean nullable) {
        check(in.dataAvail() <= 16384);
        String value=in.readString();
        check(value==null ? nullable : value.length()<=max);
        return value;
    }
    static boolean bool(Parcel in) { int value=in.readInt();check(value==0 || value==1);return value==1; }
    private Wire() { }
}
