#ifndef THEKAIN_LEASE_GUARD_H
#define THEKAIN_LEASE_GUARD_H
#include "runtime_kernel.h"
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
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
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog p={(unsigned short)(sizeof(ops)/sizeof(ops[0])),ops};
    return prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&p);
}
static int lease_guard_denials(void) {
    if(close(LEASE_GUARD_FD)!=-1 || errno!=EPERM)return 0;
#ifdef __NR_dup2
    if(syscall(__NR_dup2,1,LEASE_GUARD_FD)!=-1 || errno!=EPERM)return 0;
#endif
    if(syscall(__NR_dup3,1,LEASE_GUARD_FD,0)!=-1 || errno!=EPERM)return 0;
    int copy=dup(LEASE_GUARD_FD);if(copy<0)return 0;
    int denied=flock(copy,LOCK_UN)==-1 && errno==EPERM;close(copy);
    if(!denied)return 0;
#ifdef __NR_close_range
    if(syscall(__NR_close_range,0,~0U,0)!=-1 || errno!=EPERM)return 0;
#endif
    return 1;
}
#endif
