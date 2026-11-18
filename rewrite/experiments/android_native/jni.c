#include <jni.h>
#include <Python.h>
#include <pthread.h>

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int initialization_failed = 0;

JNIEXPORT jstring JNICALL Java_com_thekain_runtime_lab_NativeRuntime_call(
        JNIEnv *env, jclass klass, jstring home, jstring nativeDirectory, jstring request) {
    (void)klass;
    if ((*env)->GetStringUTFLength(env, request) > 16384) {
        (*env)->ThrowNew(env, (*env)->FindClass(env,"java/lang/IllegalArgumentException"),"REQUEST_LIMIT");
        return NULL;
    }
    pthread_mutex_lock(&lock);
    int failed = initialization_failed;
    if (!failed && !Py_IsInitialized()) {
        const char *root = (*env)->GetStringUTFChars(env, home, NULL);
        if (!root) { pthread_mutex_unlock(&lock); return NULL; }
        PyConfig config;
        PyConfig_InitIsolatedConfig(&config);
        config.site_import = 0;
        config.write_bytecode = 0;
        config.install_signal_handlers = 0;
        PyStatus status = PyConfig_SetBytesString(&config, &config.home, root);
        if (!PyStatus_Exception(status)) {
            wchar_t *base = Py_DecodeLocale(root,NULL);
            if (!base) failed = 1;
            else {
                const wchar_t *suffixes[] = {L"/lib/python3.14",L"/lib/python3.14/lib-dynload",L"/lib/python3.14/site-packages"};
                config.module_search_paths_set = 1;
                for (int i=0;i<3 && !failed;i++) {
                    size_t length=wcslen(base)+wcslen(suffixes[i])+1;
                    wchar_t *path=PyMem_RawMalloc(length*sizeof(wchar_t));
                    if (!path) { failed=1;break; }
                    wcscpy(path,base);wcscat(path,suffixes[i]);
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
        }
        if (!result) { PyErr_Clear();failed=1; }
        Py_XDECREF(response);Py_XDECREF(module);
        if (json) (*env)->ReleaseStringUTFChars(env,request,json);
        PyGILState_Release(gil);
    }
    pthread_mutex_unlock(&lock);
    if (failed) (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/RuntimeException"),"NATIVE_RUNTIME_FAILED");
    return result;
}
