package com.thekain.runtime.lab;

import android.os.ParcelFileDescriptor;
import android.system.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Read kernel seq-files through the worker's pre-opened FDs, never worker-supplied numbers. */
final class KernelDescriptors implements AutoCloseable {
    final int pid,uid;
    final long start;
    final ParcelFileDescriptor stat,status;
    KernelDescriptors(int pid,int uid,ParcelFileDescriptor stat,ParcelFileDescriptor status) throws Exception {
        if(pid<=0 || pid==android.os.Process.myPid() || uid==android.os.Process.myUid()
                || uid%100000<90000 || uid%100000>=100000)throw new IOException("NOT_ISOLATED_PEER");
        this.pid=pid;this.uid=uid;
        this.stat=ParcelFileDescriptor.dup(stat.getFileDescriptor());
        try {
            this.status=ParcelFileDescriptor.dup(status.getFileDescriptor());
            try {
                validate(this.stat,"/proc/"+pid+"/stat");validate(this.status,"/proc/"+pid+"/status");
                Sample s=sample();start=s.start;if(s.zombie || s.uid!=uid)throw new IOException("PROC_IDENTITY");
            }catch(Exception e) {this.status.close();throw e;}
        }catch(Exception e) {this.stat.close();throw e;}
    }
    static void readOnlyRegular(ParcelFileDescriptor fd,long max) throws Exception {
        StructStat info=Os.fstat(fd.getFileDescriptor());
        long flags=-1;
        // Public fcntlInt is API30+. Kernel fdinfo works on the API26 floor without reflection/JNI in owner.
        for(String line:ProcessIdentity.read("/proc/self/fdinfo/"+fd.getFd(),4096).split("\n"))
            if(line.startsWith("flags:"))flags=Long.parseLong(line.substring(6).trim(),8);
        if(!OsConstants.S_ISREG(info.st_mode) || flags<0 || (flags&OsConstants.O_ACCMODE)!=OsConstants.O_RDONLY
                || info.st_size<0 || info.st_size>max)throw new IOException("READ_ONLY_REGULAR_FD_REQUIRED");
    }
    private static void validate(ParcelFileDescriptor fd,String expected) throws Exception {
        readOnlyRegular(fd,0);
        if(!expected.equals(Os.readlink("/proc/self/fd/"+fd.getFd())))throw new IOException("NOT_KERNEL_PROC_FD");
    }
    static String read(ParcelFileDescriptor fd,int max) throws Exception {
        Os.lseek(fd.getFileDescriptor(),0,OsConstants.SEEK_SET);
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[2048];int n;
        while((n=Os.read(fd.getFileDescriptor(),buf,0,buf.length))>0) {
            if(out.size()+n>max)throw new IOException("KERNEL_RECORD_LIMIT");out.write(buf,0,n);
        }
        return new String(out.toByteArray(),StandardCharsets.US_ASCII);
    }
    static final class Sample {
        long start,rss,vm,cpu;int uid;boolean zombie;
    }
    synchronized Sample sample() throws Exception {
        String raw=read(stat,8192);int end=raw.lastIndexOf(')');
        if(end<0 || !raw.startsWith(pid+" ("))throw new IOException("PROC_STAT");
        String[] f=raw.substring(end+2).trim().split(" +");
        if(f.length<22)throw new IOException("PROC_STAT_FIELDS");
        Sample s=new Sample();s.start=Long.parseLong(f[19]);s.cpu=Long.parseLong(f[11])+Long.parseLong(f[12]);
        s.zombie=f[0].equals("Z") || f[0].equals("X");s.uid=-1;s.rss=-1;s.vm=-1;
        if(s.zombie)return s;
        for(String line:read(status,32768).split("\n")) {
            String[] v=line.trim().split("\\s+");
            if(v[0].equals("Uid:"))s.uid=Integer.parseInt(v[1]);
            if(v[0].equals("VmRSS:"))s.rss=Long.parseLong(v[1])*1024;
            if(v[0].equals("VmSize:"))s.vm=Long.parseLong(v[1])*1024;
        }
        if(s.uid!=uid || s.rss<=0 || s.vm<=0 || s.start<=0 || s.cpu<0)throw new IOException("PROC_COUNTERS");
        return s;
    }
    synchronized boolean dead() {
        try {Sample s=sample();return s.zombie;}catch(ErrnoException e) {
            return e.errno==OsConstants.ESRCH || e.errno==OsConstants.ENOENT;
        }catch(Exception e) {return false;}
    }
    @Override public void close() {try {stat.close();}catch(IOException ignored){}try {status.close();}catch(IOException ignored){}}
}
