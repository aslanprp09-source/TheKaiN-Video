#include <Python.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <sys/ptrace.h>
#include "runtime_child_fence.h"
/* Fixed binary socketpair protocol. No shell, argv URLs, JNI/ART, arbitrary Python or inherited Binder FDs. */
static int read_all(void *buffer,size_t count) {char *p=buffer;while(count) {ssize_t n=read(0,p,count);if(n<0 && errno==EINTR)continue;if(n<=0)return -1;p+=n;count-=(size_t)n;}return 0;}
static int write_all(const void *buffer,size_t count) {const char *p=buffer;while(count) {ssize_t n=write(0,p,count);if(n<0 && errno==EINTR)continue;if(n<=0)return -1;p+=n;count-=(size_t)n;}return 0;}
static int put32(uint32_t n) {n=htonl(n);return write_all(&n,4);}
static int get32(uint32_t *n) {if(read_all(n,4))return -1;*n=ntohl(*n);return 0;}
static int put64(uint64_t n) {return put32((uint32_t)(n>>32)) || put32((uint32_t)n);}
static PyObject *transport_error;
static PyObject *gate_error(const char *code) {PyErr_SetString(transport_error,code);return NULL;}
static PyObject *fetch(PyObject *module,PyObject *args) {
    (void)module;const char *url,*method;if(!PyArg_ParseTuple(args,"ss",&url,&method))return NULL;
    size_t size=strlen(url);if(size>2048 || (strcmp(method,"GET") && strcmp(method,"HEAD")))return gate_error("INVALID_URL");
    uint32_t code,length;
    if(put32(1) || put32(!strcmp(method,"HEAD")) || put32((uint32_t)size) || write_all(url,size)
            || get32(&code) || get32(&length) || length>512*1024)return gate_error("TOOL_FAILED");
    if(code) {
        if(length)return gate_error("TOOL_FAILED");
        switch(code) {case 2:return gate_error("TIMEOUT");case 8:return gate_error("TLS_ERROR");case 9:return gate_error("REQUEST_LIMIT");case 10:return gate_error("RESPONSE_LIMIT");case 14:return gate_error("OUT_OF_SCOPE_URL");default:return gate_error("TOOL_FAILED");}
    }
    PyObject *body=PyBytes_FromStringAndSize(NULL,length);if(!body)return NULL;
    if(read_all(PyBytes_AS_STRING(body),length)) {Py_DECREF(body);return gate_error("TOOL_FAILED");}return body;
}
static PyObject *started(PyObject *module,PyObject *unused) {
    (void)module;(void)unused;uint32_t ack;
    if(put32(2) || get32(&ack) || ack)return gate_error("TOOL_FAILED");Py_RETURN_NONE;
}
static PyObject *malformed(PyObject *module,PyObject *unused) {
    (void)module;(void)unused;uint32_t ack;
    if(put32(4) || put32(INT32_MAX))return gate_error("TOOL_FAILED");
    get32(&ack);return gate_error("TOOL_FAILED");
}
static PyMethodDef methods[]={{"malformed",malformed,METH_NOARGS,NULL},{"fetch",fetch,METH_VARARGS,NULL},{"started",started,METH_NOARGS,NULL},{NULL,NULL,0,NULL}};
static struct PyModuleDef transport={PyModuleDef_HEAD_INIT,"_thekain_transport",NULL,-1,methods};
PyMODINIT_FUNC PyInit__thekain_transport(void) {
    PyObject *m=PyModule_Create(&transport);if(!m)return NULL;
    transport_error=PyErr_NewException("_thekain_transport.Error",NULL,NULL);
    if(!transport_error || PyModule_AddObjectRef(m,"Error",transport_error)) {Py_DECREF(m);return NULL;}return m;
}
static int hardened(pid_t parent) {
    char path[64];snprintf(path,sizeof(path),"/proc/%d/mem",parent);
    int fd=open(path,O_RDONLY|O_CLOEXEC);if(fd>=0) {close(fd);return 0;}
    if(errno!=EACCES && errno!=EPERM)return 0;
    if(kill(parent,0)!=-1 || errno!=EPERM)return 0;
    if(kill(0,0)!=-1 || errno!=EPERM)return 0;
    if(syscall(__NR_tgkill,parent,parent,0)!=-1 || errno!=EPERM)return 0;
    if(syscall(__NR_ptrace,PTRACE_PEEKDATA,parent,0,0)!=-1 || errno!=EPERM)return 0;
    if(syscall(__NR_prlimit64,parent,RLIMIT_CPU,NULL,NULL)!=-1 || errno!=EPERM)return 0;
    if(prctl(PR_SET_PDEATHSIG,0)!=-1 || errno!=EPERM)return 0;
    if(syscall(__NR_clone,~0UL,0,0,0,0)!=-1 || errno!=EPERM)return 0;
    if(syscall(__NR_execve,"/thekain-missing-executable",NULL,NULL)!=-1 || errno!=EPERM)return 0;
    return 255;
}
static int child_run(int argc,char **argv) {
    if(argc!=3 || getuid()%100000<90000 || prctl(PR_SET_NAME,"thekain-py-lab",0,0,0))return 100;
    pid_t parent=(pid_t)strtol(argv[2],NULL,10);
    if(setup_parent_lifetime(parent))return 101;
    uint32_t size,seconds,ack,headroomMiB;char json[16385];
    if(get32(&size) || size>16384 || !size || read_all(json,size) || get32(&headroomMiB) || get32(&seconds))return 102;
    json[size]=0;
    RuntimeKernelLimits limits;
    if(configure_runtime_limits((uint64_t)headroomMiB*1024*1024,(int)seconds,&limits) || fence_native_child())return 103;
    int protection=hardened(parent);if(protection!=255)return 104;
    if(put32(3) || put64(limits.before) || put64(limits.address) || put64(limits.cpu) || put32((uint32_t)protection)
            || get32(&ack) || ack)return 105;
    if(PyImport_AppendInittab("_thekain_transport",PyInit__thekain_transport)<0)return 106;
    PyConfig config;PyConfig_InitIsolatedConfig(&config);config.site_import=0;config.write_bytecode=0;config.install_signal_handlers=0;
    PyStatus status=PyConfig_SetBytesString(&config,&config.home,"/");config.module_search_paths_set=1;
    if(!PyStatus_Exception(status))status=PyWideStringList_Append(&config.module_search_paths,L"/proc/self/fd/3");
    if(!PyStatus_Exception(status))status=Py_InitializeFromConfig(&config);PyConfig_Clear(&config);
    if(PyStatus_Exception(status))return 107;
    PyObject *cert=PyUnicode_FromString("/proc/self/fd/4"),*key=PyUnicode_FromString("/proc/self/fd/5");
    if(!cert || !key || PySys_SetObject("_thekain_fixture_cert",cert) || PySys_SetObject("_thekain_fixture_key",key))return 108;
    Py_DECREF(cert);Py_DECREF(key);
    PyObject *loader=PyImport_ImportModule("native_import");
    PyObject *installed=loader?PyObject_CallMethod(loader,"install","s",argv[1]):NULL;
    if(!installed)return 109;Py_DECREF(installed);Py_DECREF(loader);
    PyObject *module=PyImport_ImportModule("thekain_lab");
    PyObject *response=module?PyObject_CallMethod(module,"dispatch","s",json):NULL;
    if(!response || !PyUnicode_Check(response))return 110;
    Py_ssize_t length=0;const char *value=PyUnicode_AsUTF8AndSize(response,&length);
    if(!value)return 111;
    if(length>65536) {put32(5);get32(&ack);return 0;}
    if(put32(4) || put32((uint32_t)length) || write_all(value,(size_t)length))return 112;
    get32(&ack); // Do not turn a valid response into a crash race before the supervisor accepts it.
    return 0; // Kernel destroys the one interpreter and its sealed mappings; no cross-operation reuse.
}

int main(int argc,char **argv) {
    int code=child_run(argc,argv);
    if(code) {put32(6);put32((uint32_t)code);}
    return code;
}
