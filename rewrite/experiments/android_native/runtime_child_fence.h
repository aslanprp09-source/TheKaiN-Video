#ifndef THEKAIN_CHILD_FENCE_H
#define THEKAIN_CHILD_FENCE_H
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "runtime_kernel.h"
#include <signal.h>
#include <fcntl.h>
#include <sys/ioctl.h>
/* Installed after exec and before Python. No ART/Binder threads exist in this child.
 * Every possible future thread inherits this irreversible fence (clone itself is denied).
 * The separate Java supervisor is non-dumpable; the child cannot signal, ptrace,
 * change limits/scheduling, or obtain handles to another process sharing its isolated UID. */
#define DENY_NR(n) BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(n),0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM)
#define SELF_ONLY(n,zero) BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(n),0,5), \
 BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])), \
 BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(zero)?0:(unsigned int)getpid(),2,0), \
 BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned int)getpid(),1,0), \
 BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
static int setup_parent_lifetime(pid_t parent) {
    return parent>1 && prctl(PR_SET_PDEATHSIG,SIGKILL)==0 && getppid()==parent?0:-1;
}
static int limit_child_files(void) {
    struct rlimit files={64,64};
    return setrlimit(RLIMIT_NOFILE,&files) || getrlimit(RLIMIT_NOFILE,&files) || files.rlim_cur!=64 || files.rlim_max!=64?-1:0;
}
static int fence_native_child(void) {
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        DENY_NR(__NR_clone),
#ifdef __NR_clone3
        DENY_NR(__NR_clone3),
#endif
#ifdef __NR_fork
        DENY_NR(__NR_fork),
#endif
#ifdef __NR_vfork
        DENY_NR(__NR_vfork),
#endif
        DENY_NR(__NR_execve),
#ifdef __NR_execveat
        DENY_NR(__NR_execveat),
#endif
        #ifdef __NR_io_uring_setup
        DENY_NR(__NR_io_uring_setup),DENY_NR(__NR_io_uring_enter),DENY_NR(__NR_io_uring_register),
#endif
#ifdef __NR_kcmp
        DENY_NR(__NR_kcmp),
#endif
        // Bootstrap memfds are created/sealed by the supervisor, never by Python.
        // Otherwise write()+unmap could retain writable shmem outside sampled RSS/AS.
        DENY_NR(__NR_memfd_create),
#ifdef __NR_shmget
        DENY_NR(__NR_shmget),DENY_NR(__NR_shmat),DENY_NR(__NR_shmctl),
#endif
        DENY_NR(__NR_ptrace),DENY_NR(__NR_process_vm_readv),DENY_NR(__NR_process_vm_writev),
        // Do not permit credential/namespace changes that could clear PDEATHSIG.
        DENY_NR(__NR_setuid),DENY_NR(__NR_setgid),DENY_NR(__NR_setreuid),DENY_NR(__NR_setregid),
        DENY_NR(__NR_setresuid),DENY_NR(__NR_setresgid),DENY_NR(__NR_setfsuid),DENY_NR(__NR_setfsgid),
        DENY_NR(__NR_setgroups),DENY_NR(__NR_capset),DENY_NR(__NR_unshare),DENY_NR(__NR_setns),
        // No indirect SIGIO/FIOSETOWN route to the same-UID supervisor.
        DENY_NR(__NR_ioctl),DENY_NR(__NR_setsockopt),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_fcntl,0,8),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,F_SETOWN,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,F_SETOWN_EX,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,F_SETSIG,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        DENY_NR(__NR_prctl),DENY_NR(__NR_tkill),DENY_NR(__NR_rt_sigqueueinfo),DENY_NR(__NR_rt_tgsigqueueinfo),
        DENY_NR(__NR_setpriority),DENY_NR(__NR_sched_setparam),DENY_NR(__NR_sched_setscheduler),DENY_NR(__NR_sched_setaffinity),
#ifdef __NR_sched_setattr
        DENY_NR(__NR_sched_setattr),
#endif
#ifdef __NR_pidfd_send_signal
        DENY_NR(__NR_pidfd_send_signal),
#endif
#ifdef __NR_pidfd_getfd
        DENY_NR(__NR_pidfd_getfd),
#endif
#ifdef __NR_process_madvise
        DENY_NR(__NR_process_madvise),
#endif
#ifdef __NR_process_mrelease
        DENY_NR(__NR_process_mrelease),
#endif
        SELF_ONLY(__NR_kill,0),SELF_ONLY(__NR_tgkill,0),SELF_ONLY(__NR_prlimit64,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={(unsigned short)(sizeof(filter)/sizeof(filter[0])),filter};
    return prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program);
}
#undef DENY_NR
#undef SELF_ONLY
#endif
