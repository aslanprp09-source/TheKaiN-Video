#ifndef THEKAIN_SEALED_CODE_HOOK
#define THEKAIN_SEALED_CODE_HOOK
#include <Python.h>
/* zipimport uses the public open_code hook even during encodings bootstrap.
 * Reuse the immutable inherited FD: never reopen a memfd path under isolated_app.
 * The child is single-threaded; each zip read seeks before use. closefd=False
 * prevents one import's context manager from closing the lifetime resource FD. */
static unsigned long sealed_code_reads;
static PyObject *sealed_code_open(PyObject *path,void *unused) {
    (void)unused;
    if(!PyUnicode_Check(path) || PyUnicode_CompareWithASCIIString(path,"/proc/self/fd/3")!=0) {
        PyErr_SetString(PyExc_PermissionError,"ONLY_VERIFIED_SEALED_CODE");return NULL;
    }
    sealed_code_reads++;
    return PyFile_FromFd(3,NULL,"rb",-1,NULL,NULL,NULL,0);
}
#endif
