#ifndef THEKAIN_MEMORY_TLS_H
#define THEKAIN_MEMORY_TLS_H
#include <openssl/ssl.h>
#include <openssl/pem.h>
#include <sys/stat.h>
/* Same pinned OpenSSL as _ssl. No fopen/proc-FD reopening, socket or release key. */
static int sealed_pem(int fd,unsigned char *buffer,size_t cap) {
    struct stat st;if(fstat(fd,&st) || !S_ISREG(st.st_mode) || st.st_size<=0 || (uint64_t)st.st_size>cap || lseek(fd,0,SEEK_SET)<0)return -1;
    size_t done=0;while(done<(size_t)st.st_size) {ssize_t n=read(fd,buffer+done,(size_t)st.st_size-done);if(n<0 && errno==EINTR)continue;if(n<=0)return -1;done+=(size_t)n;}return (int)done;
}
static PyObject *memory_tls(PyObject *module,PyObject *args) {
    (void)module;const char *version;if(!PyArg_ParseTuple(args,"s",&version))return NULL;
    int ok=0;unsigned char cert[16384],key[16384];int clen=sealed_pem(4,cert,sizeof(cert)),klen=sealed_pem(5,key,sizeof(key));
    BIO *cb=NULL,*kb=NULL,*client_bio=NULL,*server_bio=NULL;X509 *x=NULL;EVP_PKEY *pkey=NULL;
    SSL_CTX *client_ctx=NULL,*server_ctx=NULL;SSL *client=NULL,*server=NULL;
    if(clen<=0 || klen<=0 || strcmp(version,OpenSSL_version(OPENSSL_VERSION)))goto done;
    cb=BIO_new_mem_buf(cert,clen);kb=BIO_new_mem_buf(key,klen);if(!cb || !kb)goto done;
    x=PEM_read_bio_X509(cb,NULL,NULL,NULL);pkey=PEM_read_bio_PrivateKey(kb,NULL,NULL,NULL);if(!x || !pkey)goto done;
    client_ctx=SSL_CTX_new(TLS_client_method());server_ctx=SSL_CTX_new(TLS_server_method());if(!client_ctx || !server_ctx)goto done;
    if(!SSL_CTX_set_min_proto_version(client_ctx,TLS1_2_VERSION) || !SSL_CTX_set_min_proto_version(server_ctx,TLS1_2_VERSION)
            || SSL_CTX_use_certificate(server_ctx,x)!=1 || SSL_CTX_use_PrivateKey(server_ctx,pkey)!=1
            || SSL_CTX_check_private_key(server_ctx)!=1 || X509_STORE_add_cert(SSL_CTX_get_cert_store(client_ctx),x)!=1)goto done;
    SSL_CTX_set_verify(client_ctx,SSL_VERIFY_PEER,NULL);
    client=SSL_new(client_ctx);server=SSL_new(server_ctx);if(!client || !server)goto done;
    if(X509_VERIFY_PARAM_set1_ip_asc(SSL_get0_param(client),"127.0.0.1")!=1 || BIO_new_bio_pair(&client_bio,0,&server_bio,0)!=1)goto done;
    SSL_set_bio(client,client_bio,client_bio);client_bio=NULL;SSL_set_bio(server,server_bio,server_bio);server_bio=NULL;
    SSL_set_connect_state(client);SSL_set_accept_state(server);
    for(int i=0;i<128;i++) {
        SSL *peers[2]={client,server};
        for(int j=0;j<2;j++)if(!SSL_is_init_finished(peers[j])) {
            int result=SSL_do_handshake(peers[j]);
            if(result!=1) {int error=SSL_get_error(peers[j],result);if(error!=SSL_ERROR_WANT_READ && error!=SSL_ERROR_WANT_WRITE)goto done;}
        }
        if(SSL_is_init_finished(client) && SSL_is_init_finished(server)) {
            int v=SSL_version(client);ok=(v==TLS1_2_VERSION || v==TLS1_3_VERSION) && SSL_get_verify_result(client)==X509_V_OK;break;
        }
    }
done:
    SSL_free(client);SSL_free(server);SSL_CTX_free(client_ctx);SSL_CTX_free(server_ctx);
    BIO_free(client_bio);BIO_free(server_bio);BIO_free(cb);BIO_free(kb);X509_free(x);EVP_PKEY_free(pkey);
    OPENSSL_cleanse(key,sizeof(key));return PyBool_FromLong(ok);
}
#endif
