#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>
/* Owner-only libc JNI. No libpython, signals, worker PID or private key access. */
static int owner(void) {int uid=getuid()%100000;return uid>=10000 && uid<90000;}
static void failure(JNIEnv *env) {(*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),"LEASE_KERNEL_PROOF_FAILED");}
JNIEXPORT jint JNICALL Java_com_thekain_runtime_lab_LeaseNative_create(JNIEnv *env,jclass cls,jstring path) {
    (void)cls;if(!owner()) {failure(env);return -1;}
    const char *name=(*env)->GetStringUTFChars(env,path,NULL);if(!name)return -1;
    int fd=open(name,O_CREAT|O_EXCL|O_RDONLY|O_CLOEXEC|O_NOFOLLOW,0600);
    int ok=fd>=0 && flock(fd,LOCK_EX|LOCK_NB)==0;
    if(!ok && fd>=0) {close(fd);unlink(name);}
    (*env)->ReleaseStringUTFChars(env,path,name);
    if(!ok) {failure(env);return -1;}return fd;
}
JNIEXPORT jint JNICALL Java_com_thekain_runtime_lab_LeaseNative_acquire(JNIEnv *env,jclass cls,jstring path,jlong device,jlong inode) {
    (void)cls;if(!owner() || device<0 || inode<=0) {failure(env);return -1;}
    const char *name=(*env)->GetStringUTFChars(env,path,NULL);if(!name)return -1;
    int fd=open(name,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);(*env)->ReleaseStringUTFChars(env,path,name);
    struct stat st;
    if(fd<0 || fstat(fd,&st) || !S_ISREG(st.st_mode) || st.st_size!=0 || st.st_nlink!=1
            || st.st_uid!=getuid() || (st.st_mode&0777)!=0600
            || (uint64_t)st.st_dev!=(uint64_t)device || (uint64_t)st.st_ino!=(uint64_t)inode)goto fail;
    if(flock(fd,LOCK_EX|LOCK_NB)) {
        int busy=errno==EWOULDBLOCK || errno==EAGAIN;close(fd);
        if(busy)return -1;failure(env);return -1;
    }
    return fd; /* Caller holds exclusive proof while committing cleanup. */
fail:if(fd>=0)close(fd);failure(env);return -1;
}
