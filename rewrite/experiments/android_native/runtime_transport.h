#ifndef THEKAIN_RUNTIME_TRANSPORT_H
#define THEKAIN_RUNTIME_TRANSPORT_H

static JNIEnv *transport_env;
static jobject transport_gate;
static pthread_t transport_thread;
static PyObject *transport_error;

static PyObject *transport_failure(const char *code) {
    PyErr_SetString(transport_error,code);return NULL;
}
static const char *transport_code(int code) {
    switch(code) {
        case 2:return "TIMEOUT";
        case 8:return "TLS_ERROR";
        case 9:return "REQUEST_LIMIT";
        case 10:return "RESPONSE_LIMIT";
        case 14:return "OUT_OF_SCOPE_URL";
        default:return "TOOL_FAILED";
    }
}
static PyObject *gate_fetch(PyObject *module,PyObject *args) {
    (void)module;const char *url,*method;
    if(!PyArg_ParseTuple(args,"ss",&url,&method))return NULL;
    if(!transport_env || !transport_gate || !pthread_equal(transport_thread,pthread_self()))return transport_failure("TOOL_FAILED");
    if(strlen(url)>2048 || (strcmp(method,"GET") && strcmp(method,"HEAD")))return transport_failure("INVALID_URL");
    JNIEnv *env=transport_env;
    jclass cls=(*env)->GetObjectClass(env,transport_gate);
    jmethodID fetch=(*env)->GetMethodID(env,cls,"fetch","(Ljava/lang/String;Ljava/lang/String;)[B");
    jstring address=(*env)->NewStringUTF(env,url),verb=(*env)->NewStringUTF(env,method);
    jbyteArray body=NULL;
    if(fetch && address && verb)body=(jbyteArray)(*env)->CallObjectMethod(env,transport_gate,fetch,address,verb);
    int code=12;
    if((*env)->ExceptionCheck(env)) {
        jthrowable error=(*env)->ExceptionOccurred(env);(*env)->ExceptionClear(env);
        jclass error_class=(*env)->GetObjectClass(env,error);
        jfieldID field=(*env)->GetFieldID(env,error_class,"code","I");
        if(field)code=(*env)->GetIntField(env,error,field);
        if((*env)->ExceptionCheck(env))(*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env,error_class);(*env)->DeleteLocalRef(env,error);
    }
    PyObject *result=NULL;
    if(body) {
        jsize length=(*env)->GetArrayLength(env,body);
        if(length<=512*1024) {
            result=PyBytes_FromStringAndSize(NULL,length);
            if(result)(*env)->GetByteArrayRegion(env,body,0,length,(jbyte*)PyBytes_AS_STRING(result));
        }
        (*env)->DeleteLocalRef(env,body);
    }
    if(address)(*env)->DeleteLocalRef(env,address);
    if(verb)(*env)->DeleteLocalRef(env,verb);
    (*env)->DeleteLocalRef(env,cls);
    if(!result)return transport_failure(transport_code(code));
    return result;
}
static PyObject *gate_started(PyObject *module,PyObject *unused) {
    (void)module;(void)unused;
    if(!transport_env || !pthread_equal(transport_thread,pthread_self()))return transport_failure("TOOL_FAILED");
    JNIEnv *env=transport_env;jclass cls=(*env)->GetObjectClass(env,transport_gate);
    jmethodID method=(*env)->GetMethodID(env,cls,"started","()V");
    if(method)(*env)->CallVoidMethod(env,transport_gate,method);
    (*env)->DeleteLocalRef(env,cls);
    if((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env);return transport_failure("TOOL_FAILED"); }
    Py_RETURN_NONE;
}
static PyMethodDef transport_methods[]={
    {"fetch",gate_fetch,METH_VARARGS,"Owned Java HTTPS transport with external counters."},
    {"started",gate_started,METH_NOARGS,"Signal actual Python dispatch entry."},
    {NULL,NULL,0,NULL}
};
static struct PyModuleDef transport_module={PyModuleDef_HEAD_INIT,"_thekain_transport",NULL,-1,transport_methods};
PyMODINIT_FUNC PyInit__thekain_transport(void) {
    PyObject *module=PyModule_Create(&transport_module);if(!module)return NULL;
    transport_error=PyErr_NewException("_thekain_transport.Error",NULL,NULL);
    if(!transport_error || PyModule_AddObjectRef(module,"Error",transport_error)<0) { Py_DECREF(module);return NULL; }
    return module;
}
#endif
