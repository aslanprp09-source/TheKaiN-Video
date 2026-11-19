#include <jni.h>
#include <Python.h>
#include <pthread.h>
#include <linux/memfd.h>
#include <fcntl.h>
#include "runtime_limits.h"
#include "runtime_transport.h"

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int initialization_failed = 0;

/* A received FD is readable but /proc/self/fd reopening still checks inode DAC.
 * Copy into isolated-owned anonymous storage and irreversibly seal it before Python.
 * No chmod, pathname, shared directory, writable descriptor or executable mapping. */
JNIEXPORT jint JNICALL Java_com_thekain_runtime_lab_IsolatedChild_sealedCopy(
        JNIEnv *env,jclass cls,jint source,jlong max) {
    (void)cls;int target=-1;long long total=0;char bytes[16384];ssize_t n;
    if(max<=0 || max>160LL*1024*1024 || lseek(source,0,SEEK_SET)<0)goto fail;
    target=(int)syscall(__NR_memfd_create,"thekain-verified-resource",MFD_CLOEXEC|MFD_ALLOW_SEALING);
    if(target<0)goto fail;
    while((n=read(source,bytes,sizeof(bytes)))>0) {
        total+=n;if(total>max)goto fail;
        ssize_t offset=0;while(offset<n) {ssize_t written=write(target,bytes+offset,(size_t)(n-offset));if(written<=0)goto fail;offset+=written;}
    }
    if(n<0 || total<=0 || fcntl(target,F_ADD_SEALS,F_SEAL_WRITE|F_SEAL_GROW|F_SEAL_SHRINK|F_SEAL_SEAL)
            || lseek(target,0,SEEK_SET)<0)goto fail;
    return target;
fail:
    if(target>=0)close(target);
    (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/RuntimeException"),"SEALED_RESOURCE_UNAVAILABLE");
    return -1;
}

static jstring runtime_call(
        JNIEnv *env, jclass klass, jstring home, jstring nativeDirectory, jstring request, jobject gate,int isolated) {
    (void)klass;
    if ((*env)->GetStringUTFLength(env, request) > 16384) {
        (*env)->ThrowNew(env, (*env)->FindClass(env,"java/lang/IllegalArgumentException"),"REQUEST_LIMIT");
        return NULL;
    }
    pthread_mutex_lock(&lock);
    transport_env=env;transport_gate=gate;transport_thread=pthread_self();
    int failed = initialization_failed;
    if (!failed && !Py_IsInitialized()) {
        const char *root = (*env)->GetStringUTFChars(env, home, NULL);
        if (!root) { pthread_mutex_unlock(&lock); return NULL; }
        if (PyImport_AppendInittab("_thekain_transport", PyInit__thekain_transport) == -1) {
            pthread_mutex_unlock(&lock); return NULL;
        }
        PyConfig config;
        PyConfig_InitIsolatedConfig(&config);
        config.site_import = 0;
        config.write_bytecode = 0;
        config.install_signal_handlers = 0;
        int resource_fd=-1,cert_fd=-1,key_fd=-1;char extra;
        char zip_path[64],cert_path[64],key_path[64];
        if(isolated) {
            if(sscanf(root,"fd:%d:%d:%d%c",&resource_fd,&cert_fd,&key_fd,&extra)!=3
                    || resource_fd<0 || cert_fd<0 || key_fd<0)failed=1;
            snprintf(zip_path,sizeof(zip_path),"/proc/self/fd/%d",resource_fd);
            snprintf(cert_path,sizeof(cert_path),"/proc/self/fd/%d",cert_fd);
            snprintf(key_path,sizeof(key_path),"/proc/self/fd/%d",key_fd);
        }
        PyStatus status = PyConfig_SetBytesString(&config, &config.home, isolated?"/":root);
        if (!PyStatus_Exception(status)) {
            wchar_t *base = Py_DecodeLocale(isolated?zip_path:root,NULL);
            if (!base) failed = 1;
            else {
                const wchar_t *suffixes[] = {L"/lib/python3.14",L"/lib/python3.14/lib-dynload",L"/lib/python3.14/site-packages"};
                config.module_search_paths_set = 1;
                for (int i=0;i<(isolated?1:3) && !failed;i++) {
                    const wchar_t *suffix=isolated?L"":suffixes[i];
                    size_t length=wcslen(base)+wcslen(suffix)+1;
                    wchar_t *path=PyMem_RawMalloc(length*sizeof(wchar_t));
                    if (!path) { failed=1;break; }
                    wcscpy(path,base);wcscat(path,suffix);
                    status=PyWideStringList_Append(&config.module_search_paths,path);
                    PyMem_RawFree(path);
                    if (PyStatus_Exception(status)) failed=1;
                }
                PyMem_RawFree(base);
            }
        }
        if (!failed && !PyStatus_Exception(status)) status=Py_InitializeFromConfig(&config);
        if (PyStatus_Exception(status)) failed=1;
        initialization_failed=failed;
        PyConfig_Clear(&config);
        (*env)->ReleaseStringUTFChars(env,home,root);
        if (!failed) {
            if(isolated) {
                PyObject *cert=PyUnicode_FromString(cert_path),*key=PyUnicode_FromString(key_path);
                if(!cert || !key || PySys_SetObject("_thekain_fixture_cert",cert)
                        || PySys_SetObject("_thekain_fixture_key",key))failed=1;
                Py_XDECREF(cert);Py_XDECREF(key);
            }
            const char *native = (*env)->GetStringUTFChars(env,nativeDirectory,NULL);
            PyObject *loader=PyImport_ImportModule("native_import");
            PyObject *installed=(loader && native) ? PyObject_CallMethod(loader,"install","s",native) : NULL;
            if (!installed) { PyErr_Clear();failed=1;initialization_failed=1; }
            Py_XDECREF(installed);Py_XDECREF(loader);
            if (native) (*env)->ReleaseStringUTFChars(env,nativeDirectory,native);
            PyEval_SaveThread();
        }
    }
    jstring result=NULL;
    if (!failed) {
        PyGILState_STATE gil=PyGILState_Ensure();
        const char *json=(*env)->GetStringUTFChars(env,request,NULL);
        PyObject *module=PyImport_ImportModule("thekain_lab");
        PyObject *response=(module && json) ? PyObject_CallMethod(module,"dispatch","s",json) : NULL;
        if (response && PyUnicode_Check(response)) {
            Py_ssize_t size=0;
            const char *value=PyUnicode_AsUTF8AndSize(response,&size);
            if (value && size<=65536) result=(*env)->NewStringUTF(env,value);
            else if (value) (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/RuntimeException"),"OUTPUT_LIMIT");
        }
        if (!result) { PyErr_Clear();failed=1; }
        Py_XDECREF(response);Py_XDECREF(module);
        if (json) (*env)->ReleaseStringUTFChars(env,request,json);
        PyGILState_Release(gil);
    }
    transport_env=NULL;transport_gate=NULL;
    pthread_mutex_unlock(&lock);
    if (failed && !(*env)->ExceptionCheck(env)) (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/RuntimeException"),"NATIVE_RUNTIME_FAILED");
    return result;
}

JNIEXPORT jstring JNICALL Java_com_thekain_runtime_lab_ChildRuntime_call(
        JNIEnv *env,jclass cls,jstring home,jstring nativeDirectory,jstring request,jobject gate) {
    return runtime_call(env,cls,home,nativeDirectory,request,gate,0);
}
JNIEXPORT jstring JNICALL Java_com_thekain_runtime_lab_IsolatedChild_call(
        JNIEnv *env,jclass cls,jstring home,jstring nativeDirectory,jstring request,jobject gate) {
    return runtime_call(env,cls,home,nativeDirectory,request,gate,1);
}
JNIEXPORT jlongArray JNICALL Java_com_thekain_runtime_lab_IsolatedChild_configureBudget(
        JNIEnv *env,jclass cls,jlong headroom,jint seconds) {
    jlongArray limits=Java_com_thekain_runtime_lab_ChildRuntime_configureBudget(env,cls,headroom,seconds);
    if(!limits)return NULL;
    /* The isolated Python thread cannot create descendants that outlive the Service.
     * Existing ART/Binder threads are untouched. Fixed lab extraction needs no new threads. */
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_clone,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
#ifdef __NR_clone3
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_clone3,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
#endif
#ifdef __NR_fork
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_fork,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
#endif
#ifdef __NR_vfork
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_vfork,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
#endif
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_execve,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
#ifdef __NR_execveat
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,__NR_execveat,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
#endif
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={(unsigned short)(sizeof(filter)/sizeof(filter[0])),filter};
    if(prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program))goto fail;
    /* CLONE_THREAD without CLONE_VM/SIGHAND cannot spawn, even if the fence were missing. */
    if(syscall(__NR_clone,0x00010000UL,0,0,0,0)!=-1 || errno!=EPERM)goto fail;
    if(syscall(__NR_execve,"/thekain-never-exists",NULL,NULL)!=-1 || errno!=EPERM)goto fail;
    return limits;
fail:
    (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/RuntimeException"),"ISOLATED_SPAWN_FENCE_UNAVAILABLE");
    return NULL;
}
