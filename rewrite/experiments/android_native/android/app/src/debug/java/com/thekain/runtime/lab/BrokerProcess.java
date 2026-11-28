package com.thekain.runtime.lab;
import android.system.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Kernel identity; a zombie leader, Binder death or an unreadable proc file is not process absence. */
final class BrokerProcess {
    // /proc stat field9 exports task->flags. Linux sets PF_EXITING before exit_mm,
    // which can remove VmRSS while the task state is still R (before EXIT_ZOMBIE).
    static final long PF_EXITING=0x00000004L;
    final int pid;final long start,cpu,rss,flags;final String state;
    BrokerProcess(int pid,long start,long cpu,long rss,String state,long flags) {this.pid=pid;this.start=start;this.cpu=cpu;this.rss=rss;this.state=state;this.flags=flags;}
    boolean exiting() {return (flags&PF_EXITING)!=0;}
    boolean dying() {return state.equals("Z") || state.equals("X") || exiting();}
    static String observationCategory(Exception error) {
        if(error instanceof MissingRss)return "RSS_MISSING";
        if(error instanceof NumberFormatException)return "PROC_NUMBER";
        String code=error.getMessage();
        if("BROKER_PID_CHANGED".equals(code))return "PROC_IDENTITY_CHANGED";
        if("BROKER_RSS".equals(code))return "RSS_INVALID";
        if("BROKER_PROC".equals(code))return "PROC_PARSE";
        if("BROKER_FLAGS".equals(code))return "FLAGS_INVALID";
        if("BROKER_PROC_LIMIT".equals(code))return "PROC_OVERSIZED";
        if("BROKER_EXIT_REGRESSED".equals(code))return "EXIT_REGRESSED";
        return "PROC_OTHER"; // Never log arbitrary exception text.
    }
    interface ProcReader {String read(String path,int max) throws Exception;}
    private static final class MissingRss extends IOException {MissingRss() {super("BROKER_RSS_MISSING");}}
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
        if(rss<0)throw new MissingRss();return rss;
    }
    private static BrokerProcess stat(int pid,String raw) throws IOException {
        if(pid<=0)throw new IOException("BROKER_PID");
        int end=raw.lastIndexOf(')');if(!raw.startsWith(pid+" (") || end<0)throw new IOException("BROKER_PROC");
        String[] fields=raw.substring(end+2).trim().split(" +");if(fields.length<22)throw new IOException("BROKER_PROC");
        try {
            if(!fields[6].matches("[0-9]{1,10}"))throw new IOException("BROKER_FLAGS");
            long flags=Long.parseLong(fields[6]);if(flags>0xffffffffL)throw new IOException("BROKER_FLAGS");
            long start=Long.parseLong(fields[19]),user=Long.parseLong(fields[11]),system=Long.parseLong(fields[12]);
            if(start<=0 || user<0 || system<0 || user>Long.MAX_VALUE-system || !fields[0].matches("[RSDZTWtXxKPI]"))throw new IOException("BROKER_PROC");
            return new BrokerProcess(pid,start,user+system,0,fields[0],flags);
        }catch(NumberFormatException invalid) {throw new IOException("BROKER_PROC");}
    }
    static BrokerProcess read(int pid) throws Exception {return read(pid,BrokerProcess::kernel);}
    // Trusted parser test seam only; no path or reader is accepted from IPC.
    static BrokerProcess read(int pid,ProcReader reader) throws Exception {
        if(pid<=0)throw new IOException("BROKER_PID");
        String path="/proc/"+pid;BrokerProcess before=stat(pid,reader.read(path+"/stat",8192));
        // VmRSS has explicit kernel kB units; libc/compat page size need not describe stat RSS units.
        long rss=0;MissingRss missing=null;
        if(!before.dying())try {rss=rssFromStatus(reader.read(path+"/status",32768));}catch(MissingRss absent) {missing=absent;}
        BrokerProcess after=stat(pid,reader.read(path+"/stat",8192));
        if(after.start!=before.start)throw new IOException("BROKER_PID_CHANGED");
        if(before.dying() && !after.dying())throw new IOException("BROKER_EXIT_REGRESSED");
        // A running process may lose VmRSS between stat/status while it becomes a zombie.
        // Only a confirmed same-identity dying state explains missing RSS. Unknown is an error.
        if(missing!=null && !after.dying())throw missing;
        return new BrokerProcess(pid,after.start,after.cpu,after.dying()?0:rss,after.state,after.flags);
    }
    static boolean absent(int pid,long start) throws Exception {return absent(pid,start,BrokerProcess::kernel);}
    static boolean absent(int pid,long start,ProcReader reader) throws Exception {
        try {return read(pid,reader).start!=start;}
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
