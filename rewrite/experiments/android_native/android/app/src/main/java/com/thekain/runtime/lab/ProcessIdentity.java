package com.thekain.runtime.lab;

import android.os.Process;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** PID alone is insufficient: validate UID, exact process name and kernel start ticks. */
final class ProcessIdentity {
    static final String NAME="com.thekain.runtime.lab:python_runtime";
    final int pid;
    final long startTicks,rssBytes,virtualBytes,cpuTicks;
    final int uid;
    static final long CLOCK_TICKS=clockTicks();
    final boolean zombie;
    private ProcessIdentity(int pid,long ticks,long rss,long virtual,boolean zombie,long cpu,int uid) {
        this.pid=pid;startTicks=ticks;rssBytes=rss;virtualBytes=virtual;this.zombie=zombie;cpuTicks=cpu;this.uid=uid;
    }
    private static long clockTicks() {
        long ticks=android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK);
        if(ticks<=0 || ticks>100000)throw new IllegalStateException("INVALID_CLOCK_TICKS");
        return ticks;
    }
    static long selfStartTicks() throws IOException {
        String stat=read("/proc/self/stat",4096);int end=stat.lastIndexOf(')');
        if(end<0)throw new IOException("PROC_STAT");
        return Long.parseLong(stat.substring(end+2).trim().split(" +")[19]);
    }
    static String read(String path,int max) throws IOException {
        try(InputStream in=new FileInputStream(path)) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[2048];int n;
            while((n=in.read(bytes))!=-1) { if(out.size()+n>max)throw new IOException("PROC_LIMIT");out.write(bytes,0,n); }
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    static boolean inWorker() {
        try { return read("/proc/self/cmdline",512).split("\u0000",2)[0].equals(NAME); }
        catch(IOException e) { return false; }
    }
    static ProcessIdentity worker(int pid) throws IOException {
        if(pid<=0 || (pid==Process.myPid() && !inWorker())) throw new IOException("NOT_CHILD");
        String base="/proc/"+pid+"/";
        String stat=read(base+"stat",4096);int end=stat.lastIndexOf(')');
        if(end<0)throw new IOException("PROC_STAT");
        String[] fields=stat.substring(end+2).trim().split(" +");
        if(fields.length<20)throw new IOException("PROC_STAT");
        boolean zombie=fields[0].equals("Z");long start=Long.parseLong(fields[19]);
        long cpu=Long.parseLong(fields[11])+Long.parseLong(fields[12]);
        if(cpu<0)throw new IOException("PROC_CPU");
        if(zombie)return new ProcessIdentity(pid,start,0,0,true,cpu,-1);
        if(!read(base+"cmdline",512).split("\u0000",2)[0].equals(NAME))throw new IOException("NOT_WORKER");
        String status=read(base+"status",32768);long uid=-1,rss=-1,vm=-1;
        for(String line:status.split("\n")) {
            String[] values=line.trim().split("\\s+");
            if(values[0].equals("Uid:"))uid=Long.parseLong(values[1]);
            if(values[0].equals("VmRSS:"))rss=Long.parseLong(values[1])*1024;
            if(values[0].equals("VmSize:"))vm=Long.parseLong(values[1])*1024;
        }
        if(uid!=Process.myUid() || rss<0 || vm<0)throw new IOException("WORKER_IDENTITY");
        return new ProcessIdentity(pid,start,rss,vm,false,cpu,(int)uid);
    }
    boolean same(ProcessIdentity other) { return other.pid==pid && other.startTicks==startTicks; }
    boolean exited() {
        try { ProcessIdentity now=worker(pid);return !same(now) || now.zombie; }
        catch(FileNotFoundException e) { return true; }
        catch(IOException|RuntimeException e) { return !new File("/proc/"+pid).exists(); }
    }
    void killOwned() throws IOException {
        if(pid==Process.myPid())throw new IOException("REFUSE_SELF_KILL");
        ProcessIdentity now=worker(pid);
        if(!same(now))throw new IOException("PID_REUSED");
        if(!now.zombie)Process.killProcess(pid);
    }
}
