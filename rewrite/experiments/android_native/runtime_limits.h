#ifndef THEKAIN_RUNTIME_LIMITS_H
#define THEKAIN_RUNTIME_LIMITS_H
#include "runtime_kernel.h"
JNIEXPORT jlongArray JNICALL Java_com_thekain_runtime_lab_ChildRuntime_configureBudget(
        JNIEnv *env,jclass klass,jlong headroom,jint seconds) {
    (void)klass;RuntimeKernelLimits limits;
    if(configure_runtime_limits((unsigned long long)headroom,seconds,&limits)) {
        (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/RuntimeException"),"KERNEL_LIMIT_UNAVAILABLE");return NULL;
    }
    jlong values[4]={(jlong)limits.before,(jlong)limits.address,(jlong)limits.cpu,limits.socket_denied};
    jlongArray result=(*env)->NewLongArray(env,4);
    if(result)(*env)->SetLongArrayRegion(env,result,0,4,values);
    return result;
}
#endif
