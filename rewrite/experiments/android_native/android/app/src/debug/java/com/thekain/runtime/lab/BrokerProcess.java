package com.thekain.runtime.lab;
import android.system.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Kernel identity; a zombie leader, Binder death or an unreadable proc file is not process absence. */
final class BrokerProcess {
    final int pid;final long start,cpu,rss;
    BrokerProcess(int pid,long start,long cpu,long rss) {this.pid=pid;this.start=start;this.cpu=cpu;this.rss=rss;}
    private static String kernel(String path,int max) throws Exception {
        java.io.FileDescriptor fd=Os.open(path,OsConstants.O_RDONLY|0x80000|OsConstants.O_NOFOLLOW,0);
        try {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int n;
            while((n=Os.read(fd,buffer,0,buffer.length))>0) {if(out.size()+n>max)throw new IOException("BROKER_PROC_LIMIT");out.write(buffer,0,n);}
            return new String(out.toByteArray(),StandardCharsets.US_ASCII);
        }finally {Os.close(fd);}
    }
    static long rssFromStatus(String status) throws IOException {
        long rss=-1;
        for(String line:status.split("\n"))if(line.startsWith("VmRSS:")) {
            String[] fields=line.trim().split("\\s+");
            if(rss!=-1 || fields.length!=3 || !fields[1].matches("[0-9]{1,16}") || !fields[2].equals("kB"))throw new IOException("BROKER_RSS");
            long kib=Long.parseLong(fields[1]);if(kib>Long.MAX_VALUE/1024)throw new IOException("BROKER_RSS");rss=kib*1024;
        }
        if(rss<0)throw new IOException("BROKER_RSS");return rss;
    }
    static BrokerProcess read(int pid) throws Exception {
        if(pid<=0)throw new IOException("BROKER_PID");
        String raw=kernel("/proc/"+pid+"/stat",8192);
        int end=raw.lastIndexOf(')');if(!raw.startsWith(pid+" (") || end<0)throw new IOException("BROKER_PROC");
        String[] fields=raw.substring(end+2).trim().split(" +");if(fields.length<22)throw new IOException("BROKER_PROC");
        long start=Long.parseLong(fields[19]),cpu=Long.parseLong(fields[11])+Long.parseLong(fields[12]);
        if(start<=0 || cpu<0)throw new IOException("BROKER_PROC");
        // VmRSS has explicit kernel kB units; libc/compat page size need not describe stat RSS units.
        long rss=fields[0].equals("Z") || fields[0].equals("X")?0:rssFromStatus(kernel("/proc/"+pid+"/status",32768));
        String after=kernel("/proc/"+pid+"/stat",8192);int afterEnd=after.lastIndexOf(')');
        if(!after.startsWith(pid+" (") || afterEnd<0 || Long.parseLong(after.substring(afterEnd+2).trim().split(" +")[19])!=start)
            throw new IOException("BROKER_PID_CHANGED");
        return new BrokerProcess(pid,start,cpu,rss);
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
