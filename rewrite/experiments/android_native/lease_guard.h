#ifndef THEKAIN_LEASE_GUARD_H
#define THEKAIN_LEASE_GUARD_H
#include "runtime_kernel.h"
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <signal.h>
#define LEASE_GUARD_FD 6
/* A read-only, zero-length regular file. The owner locks its open file
 * description before Binder transfer; dup/fork preserve that kernel lock. */
static int lease_guard_identity(int fd,uint64_t device,uint64_t inode) {
    struct stat st;int flags=fcntl(fd,F_GETFL);
    return flags>=0 && (flags&O_ACCMODE)==O_RDONLY && fstat(fd,&st)==0
        && S_ISREG(st.st_mode) && st.st_size==0 && st.st_nlink==1
        && (uint64_t)st.st_dev==device && (uint64_t)st.st_ino==inode;
}
static int fence_lease_guard(void) {
    struct sock_filter ops[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        /* Unlocking any duplicate would unlock the shared open description. */
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_flock,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
#ifdef __NR_close_range
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_close_range,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
#endif
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_close,0,4),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,LEASE_GUARD_FD,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
#ifdef __NR_dup2
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_dup2,0,4),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,LEASE_GUARD_FD,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
#endif
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_dup3,0,4),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,LEASE_GUARD_FD,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        /* Keep POSIX/OFD lock-changing APIs out as well; duplicated guard
         * descriptors must not provide an alternate unlock/downgrade route. */
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_fcntl,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,F_SETLK,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,F_SETLKW,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
#ifdef F_OFD_SETLK
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,F_OFD_SETLK,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,F_OFD_SETLKW,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
#endif
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog p={(unsigned short)(sizeof(ops)/sizeof(ops[0])),ops};
    return prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&p);
}
/* Optional fixed bootstrap diagnostics. Never carries Python output or paths. */
static int (*lease_guard_probe_stage)(int);
static volatile sig_atomic_t lease_guard_expected_nr=-1,lease_guard_kernel_trap,lease_guard_probe_index,lease_guard_trap_mask;
static void lease_guard_sigsys(int signal,siginfo_t *info,void *context) {
    (void)context;
    /* Linux UAPI SYS_SECCOMP=1; some host libc headers omit the symbolic name. */
    if(signal!=SIGSYS || !info || info->si_code!=1 || lease_guard_expected_nr<0
            || info->si_syscall!=lease_guard_expected_nr || lease_guard_probe_index<1 || lease_guard_probe_index>10)_exit(123);
    lease_guard_kernel_trap=1;lease_guard_trap_mask|=(sig_atomic_t)(1U<<lease_guard_probe_index);
}
/* Android's pre-existing seccomp TRAP outranks an application's ERRNO filter.
 * The bootstrap-only signal handler must authenticate SYS_SECCOMP and this
 * exact in-flight syscall; no exit status or arbitrary signal counts as proof. */
static int lease_guard_denied(long nr,long a,long b,long c) {
    lease_guard_kernel_trap=0;lease_guard_expected_nr=(sig_atomic_t)nr;
    long result=syscall(nr,a,b,c);int error=errno;
    lease_guard_expected_nr=-1;
    return (result==-1 && error==EPERM) || lease_guard_kernel_trap==1;
}
#define GUARD_PROBE(n) do {lease_guard_probe_index=(n);if(lease_guard_probe_stage && lease_guard_probe_stage(n))return 0;}while(0)
static int lease_guard_denials(void) {
    GUARD_PROBE(1);
    if(!lease_guard_denied(__NR_close,LEASE_GUARD_FD,0,0))return 0;
    GUARD_PROBE(2);
#ifdef __NR_dup2
    if(!lease_guard_denied(__NR_dup2,1,LEASE_GUARD_FD,0))return 0;
#endif
    GUARD_PROBE(3);
    if(!lease_guard_denied(__NR_dup3,1,LEASE_GUARD_FD,0))return 0;
    GUARD_PROBE(4);
    int copy=(int)syscall(__NR_fcntl,LEASE_GUARD_FD,F_DUPFD_CLOEXEC,7);if(copy<0)return 0;
    GUARD_PROBE(5);
    int denied=lease_guard_denied(__NR_flock,copy,LOCK_UN,0);
    struct flock unlock={0};unlock.l_type=F_UNLCK;unlock.l_whence=SEEK_SET;
    GUARD_PROBE(6);
    denied=denied && lease_guard_denied(__NR_fcntl,copy,F_SETLK,(long)&unlock);
    GUARD_PROBE(7);
    denied=denied && lease_guard_denied(__NR_fcntl,copy,F_SETLKW,(long)&unlock);
    GUARD_PROBE(8);
#ifdef F_OFD_SETLK
    denied=denied && lease_guard_denied(__NR_fcntl,copy,F_OFD_SETLK,(long)&unlock);
    denied=denied && lease_guard_denied(__NR_fcntl,copy,F_OFD_SETLKW,(long)&unlock);
#endif
    GUARD_PROBE(9);
    syscall(__NR_close,copy);
    if(!denied)return 0;
    GUARD_PROBE(10);
#ifdef __NR_close_range
    if(!lease_guard_denied(__NR_close_range,0,~0U,0))return 0;
#endif
    return 1;
}
#undef GUARD_PROBE
#endif
