package com.thekain.runtime.lab;
import android.system.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Kernel identity; a zombie leader, Binder death or an unreadable proc file is not process absence. */
final class BrokerProcess {
    final int pid;final long start,cpu,rss;
    BrokerProcess(int pid,long start,long cpu,long rss) {this.pid=pid;this.start=start;this.cpu=cpu;this.rss=rss;}
    static BrokerProcess read(int pid) throws Exception {
        if(pid<=0)throw new IOException("BROKER_PID");
        java.io.FileDescriptor fd=Os.open("/proc/"+pid+"/stat",OsConstants.O_RDONLY|0x80000|OsConstants.O_NOFOLLOW,0);
        String raw;
        try {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int n;
            while((n=Os.read(fd,buffer,0,buffer.length))>0) {if(out.size()+n>8192)throw new IOException("BROKER_PROC_LIMIT");out.write(buffer,0,n);}
            raw=new String(out.toByteArray(),StandardCharsets.US_ASCII);
        }finally {Os.close(fd);}
        int end=raw.lastIndexOf(')');if(!raw.startsWith(pid+" (") || end<0)throw new IOException("BROKER_PROC");
        String[] fields=raw.substring(end+2).trim().split(" +");if(fields.length<22)throw new IOException("BROKER_PROC");
        long start=Long.parseLong(fields[19]),cpu=Long.parseLong(fields[11])+Long.parseLong(fields[12]);
        long pages=Long.parseLong(fields[21]),size=Os.sysconf(OsConstants._SC_PAGESIZE);
        if(start<=0 || cpu<0 || pages<0 || size<=0 || pages>Long.MAX_VALUE/size)throw new IOException("BROKER_PROC");
        return new BrokerProcess(pid,start,cpu,pages*size);
    }
    static boolean absent(int pid,long start) throws Exception {
        try {return read(pid).start!=start;}
        catch(ErrnoException e) {if(e.errno==OsConstants.ENOENT || e.errno==OsConstants.ESRCH)return true;throw e;}
    }
    static BrokerProcess authenticate(android.content.Context context,BrokerHello hello) throws Exception {
        if(hello.uid!=android.os.Process.myUid() || hello.pid==android.os.Process.myPid())throw new IOException("BROKER_IDENTITY");
        BrokerProcess kernel=read(hello.pid);
        if(kernel.start!=hello.start || !ProcessIdentity.read("/proc/"+hello.pid+"/cmdline",512).split("\u0000",2)[0].equals(RemoteHttpsService.processName(context)))
            throw new IOException("BROKER_IDENTITY");
        if(ProcessIdentity.read("/proc/"+hello.pid+"/maps",2*1024*1024).contains("libpython"))throw new IOException("BROKER_PYTHON_FORBIDDEN");
        return kernel;
    }
}
