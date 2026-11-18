#ifndef THEKAIN_RUNTIME_KERNEL_H
#define THEKAIN_RUNTIME_KERNEL_H
#include <errno.h>
#include <stdint.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

static int fence_python_network(void) {
#if defined(__x86_64__)
    const unsigned int arch=AUDIT_ARCH_X86_64;
#elif defined(__aarch64__)
    const unsigned int arch=AUDIT_ARCH_AARCH64;
#else
#error Unreviewed Android ABI
#endif
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,arch,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JGE|BPF_K,0x40000000U,4,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_socket,3,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_connect,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_socketpair,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM)
    };
    struct sock_fprog program={(unsigned short)(sizeof(filter)/sizeof(filter[0])),filter};
    // Per-thread, never TSYNC: do not change ART/Binder or the prestarted transport thread.
    if(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0))return -1;
    return prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program);
}

typedef struct { unsigned long long before,address;rlim_t cpu;int socket_denied; } RuntimeKernelLimits;
static int configure_runtime_limits(unsigned long long headroom,int seconds,RuntimeKernelLimits *result) {
    unsigned long pages=0;FILE *file=fopen("/proc/self/statm","r");
    int ok=file && fscanf(file,"%lu",&pages)==1;
    if(file)fclose(file);
    struct rusage usage;
    if(!ok || headroom<64ULL*1024*1024 || headroom>256ULL*1024*1024 || seconds<1 || seconds>32 || getrusage(RUSAGE_SELF,&usage))return -1;
    unsigned long long before=(unsigned long long)pages*(unsigned long long)sysconf(_SC_PAGESIZE);
    if(before>UINT64_MAX-headroom)return -1;
    struct rlimit address={before+headroom,before+headroom};
    rlim_t spent=(rlim_t)(usage.ru_utime.tv_sec+usage.ru_stime.tv_sec+2);
    struct rlimit cpu={spent+(rlim_t)seconds,spent+(rlim_t)seconds};
    struct rlimit core={0,0};
    if(setrlimit(RLIMIT_AS,&address) || setrlimit(RLIMIT_CPU,&cpu) || setrlimit(RLIMIT_CORE,&core)
            || getrlimit(RLIMIT_AS,&address) || getrlimit(RLIMIT_CPU,&cpu) || fence_python_network())return -1;
    int descriptor=socket(AF_INET,SOCK_STREAM,0);
    int denied=descriptor<0 && errno==EPERM;
    if(descriptor>=0)close(descriptor);
    if(!denied)return -1;
    result->before=before;result->address=address.rlim_max;result->cpu=cpu.rlim_max;result->socket_denied=1;
    return 0;
}
#endif
