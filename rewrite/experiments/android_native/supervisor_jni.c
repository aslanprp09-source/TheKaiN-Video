#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
/* No libpython dependency. Trusted supervisor only; one spawned PID in this Service lifetime. */
static pid_t child_pid;
static int reaped;
JNIEXPORT jboolean JNICALL Java_com_thekain_runtime_lab_SupervisorNative_protect(JNIEnv *env,jclass cls) {
    (void)env;(void)cls;return getuid()%100000>=90000 && prctl(PR_SET_DUMPABLE,0)==0 && prctl(PR_GET_DUMPABLE)==0;
}
static void failure(JNIEnv *env) {(*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/RuntimeException"),"NATIVE_SUPERVISOR_FAILED");}
JNIEXPORT jint JNICALL Java_com_thekain_runtime_lab_SupervisorNative_seal(JNIEnv *env,jclass cls,jint source,jlong max) {
    (void)cls;int target=-1;long long total=0;char bytes[16384];ssize_t n;
    if(max<=0 || max>160LL*1024*1024 || lseek(source,0,SEEK_SET)<0)goto fail;
    target=(int)syscall(__NR_memfd_create,"thekain-verified-resource",MFD_CLOEXEC|MFD_ALLOW_SEALING);
    if(target<0)goto fail;
    while((n=read(source,bytes,sizeof(bytes)))>0) {
        total+=n;if(total>max)goto fail;
        ssize_t offset=0;while(offset<n) {ssize_t w=write(target,bytes+offset,(size_t)(n-offset));if(w<=0)goto fail;offset+=w;}
    }
    if(n<0 || total<=0 || fcntl(target,F_ADD_SEALS,F_SEAL_WRITE|F_SEAL_GROW|F_SEAL_SHRINK|F_SEAL_SEAL) || lseek(target,0,SEEK_SET)<0)goto fail;
    return target;
fail:if(target>=0)close(target);failure(env);return -1;
}
JNIEXPORT jintArray JNICALL Java_com_thekain_runtime_lab_SupervisorNative_spawn(JNIEnv *env,jclass cls,jstring directory,jint zip,jint cert,jint key) {
    (void)cls;const char *root=(*env)->GetStringUTFChars(env,directory,NULL);if(!root)return NULL;
    int channel[2]={-1,-1},fds[5]={-1,-1,-1,-1,-1},nullfd=-1;char path[4096],ld[4160],parent[32];struct rlimit limit;
    if(child_pid || getuid()%100000<90000 || prctl(PR_SET_DUMPABLE,0) || prctl(PR_GET_DUMPABLE)!=0
            || getrlimit(RLIMIT_NOFILE,&limit) || limit.rlim_cur>1048576 || !limit.rlim_cur
            || snprintf(path,sizeof(path),"%s/libthekain_child.so",root)>=(int)sizeof(path)
            || snprintf(ld,sizeof(ld),"LD_LIBRARY_PATH=%s",root)>=(int)sizeof(ld)
            || socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,channel))goto fail;
    int buffer=32768;
    for(int i=0;i<2;i++)if(setsockopt(channel[i],SOL_SOCKET,SO_SNDBUF,&buffer,sizeof(buffer))
            || setsockopt(channel[i],SOL_SOCKET,SO_RCVBUF,&buffer,sizeof(buffer)))goto fail;
    nullfd=open("/dev/null",O_RDWR|O_CLOEXEC);if(nullfd<0)goto fail;
    int input[5]={channel[1],zip,cert,key,nullfd};
    for(int i=0;i<5;i++) {fds[i]=fcntl(input[i],F_DUPFD_CLOEXEC,10);if(fds[i]<0)goto fail;}
    snprintf(parent,sizeof(parent),"%d",getpid());
    pid_t pid=fork();
    if(pid<0)goto fail;
    if(pid==0) {
        // Async-signal-safe operations only between ART fork and exec. No Python/JNI in that interval.
        if(dup2(fds[0],0)<0 || dup2(fds[4],1)<0 || dup2(fds[4],2)<0 || dup2(fds[1],3)<0 || dup2(fds[2],4)<0 || dup2(fds[3],5)<0)_exit(126);
        for(unsigned long i=6;i<(unsigned long)limit.rlim_cur;i++)close((int)i);
        char *args[]={"thekain-py-lab",(char*)root,parent,NULL};char *vars[]={ld,"PYTHONNOUSERSITE=1",NULL};
        execve(path,args,vars);_exit(127);
    }
    child_pid=pid;
    for(int i=0;i<5;i++)close(fds[i]);close(nullfd);close(channel[1]);
    (*env)->ReleaseStringUTFChars(env,directory,root);
    jint values[2]={pid,channel[0]};jintArray result=(*env)->NewIntArray(env,2);
    if(result)(*env)->SetIntArrayRegion(env,result,0,2,values);
    else {kill(pid,SIGKILL);int status;while(waitpid(pid,&status,0)<0 && errno==EINTR){}reaped=1;close(channel[0]);}
    return result;
fail:
    for(int i=0;i<5;i++)if(fds[i]>=0)close(fds[i]);
    if(nullfd>=0)close(nullfd);for(int i=0;i<2;i++)if(channel[i]>=0)close(channel[i]);
    (*env)->ReleaseStringUTFChars(env,directory,root);failure(env);return NULL;
}
JNIEXPORT void JNICALL Java_com_thekain_runtime_lab_SupervisorNative_kill(JNIEnv *env,jclass cls,jint pid) {
    (void)cls;if(pid<=0 || pid!=child_pid || reaped || getuid()%100000<90000) {failure(env);return;}
    int status;pid_t got=waitpid(child_pid,&status,WNOHANG);
    if(got==child_pid) {reaped=1;return;}
    if(got<0 && errno==ECHILD) {reaped=1;return;}
    if(got!=0 || (kill(child_pid,SIGKILL) && errno!=ESRCH))failure(env);
}
JNIEXPORT jboolean JNICALL Java_com_thekain_runtime_lab_SupervisorNative_reap(JNIEnv *env,jclass cls,jint pid) {
    (void)cls;if(pid<=0 || pid!=child_pid) {failure(env);return JNI_FALSE;}
    if(reaped)return JNI_TRUE;
    int status;pid_t got=waitpid(child_pid,&status,WNOHANG);
    if(got==child_pid) {reaped=1;return JNI_TRUE;}return JNI_FALSE;
}
JNIEXPORT jint JNICALL Java_com_thekain_runtime_lab_SupervisorNative_dumpable(JNIEnv *env,jclass cls) {(void)env;(void)cls;return prctl(PR_GET_DUMPABLE);}

JNIEXPORT jint JNICALL Java_com_thekain_runtime_lab_SupervisorNative_flags(JNIEnv *env,jclass cls,jint fd) {
    (void)cls;int flags=fcntl(fd,F_GETFL);if(flags<0)failure(env);return flags;
}
