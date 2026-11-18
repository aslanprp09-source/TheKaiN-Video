package com.thekain.runtime.lab;

import android.os.SystemClock;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.concurrent.*;

/** HTTPS runs on a prestarted Java thread, outside the fenced Python thread. */
final class ChildNetworkGate implements AutoCloseable {
    static final int MAX_REQUESTS=12,MAX_RESPONSE=512*1024,MAX_TOTAL=2*1024*1024;
    public static final class Failure extends Exception {
        public final int code;
        Failure(int code) { super("LAB_TRANSPORT_FAILED");this.code=code; }
    }
    private final RuntimeRequest request;
    private final IRuntimeCallback callback;
    private final ThreadPoolExecutor io;
    private final SSLSocketFactory tls;
    private int requests;
    private long bytes;

    ChildNetworkGate(RuntimeRequest request,IRuntimeCallback callback,File home) throws Exception {
        this.request=request;this.callback=callback;
        if(CookieHandler.getDefault()!=null)throw new Failure(RuntimeResult.NATIVE_FAILED);
        KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);
        File ca=new File(home,"fixtures/"+(request.ca==RuntimeRequest.CA_OWN?"owned-ca.pem":"wrong-ca.pem"));
        if(ca.length()>16384)throw new Failure(RuntimeResult.INVALID_REQUEST);
        try(InputStream in=new FileInputStream(ca)) {
            store.setCertificateEntry("owned-fixture",CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(null,trust.getTrustManagers(),null);tls=ssl.getSocketFactory();
        io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),
            task->new Thread(task,"owned-https-transport"));
        io.prestartAllCoreThreads(); // Must precede installing the Python thread's seccomp filter.
    }
    public void started() throws android.os.RemoteException { callback.phase(request.id,1); }
    private int remaining() throws Failure {
        long left=request.deadline-SystemClock.elapsedRealtime();
        if(left<=0)throw new Failure(RuntimeResult.TIMEOUT);
        return (int)Math.min(left,3000);
    }
    public byte[] fetch(String url,String method) throws Failure {
        if(url==null || url.length()>2048 || !("GET".equals(method)||"HEAD".equals(method)))throw new Failure(RuntimeResult.INVALID_REQUEST);
        Future<byte[]> task=io.submit(()->fetchOnIoThread(url,method));
        try { return task.get(Math.max(1,request.deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS); }
        catch(TimeoutException e) { task.cancel(true);throw new Failure(RuntimeResult.TIMEOUT); }
        catch(ExecutionException e) {
            if(e.getCause() instanceof Failure)throw (Failure)e.getCause();
            throw new Failure(RuntimeResult.NATIVE_FAILED);
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new Failure(RuntimeResult.CANCELED); }
    }
    private byte[] fetchOnIoThread(String target,String method) throws Failure {
        for(int redirect=0;redirect<6;redirect++) {
            remaining();
            try {
                RuntimeRequest.validateUrl(target);
                URI original=new URI(request.url),uri=new URI(target);
                if(uri.getPort()!=original.getPort())throw new Failure(RuntimeResult.OUT_OF_SCOPE);
            } catch(Failure e) { throw e; }
            catch(Exception e) { throw new Failure(RuntimeResult.OUT_OF_SCOPE); }
            if(requests>=MAX_REQUESTS)throw new Failure(RuntimeResult.REQUEST_LIMIT);
            requests++;
            HttpsURLConnection connection=null;
            try {
                callback.network(request.id,requests,bytes);
                connection=(HttpsURLConnection)new URL(target).openConnection(Proxy.NO_PROXY);
                connection.setSSLSocketFactory(tls);
                connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
                connection.setConnectTimeout(remaining());connection.setReadTimeout(remaining());connection.setRequestMethod(method);
                connection.setRequestProperty("User-Agent","TheKaiN-owned-process-lab");
                connection.setRequestProperty("Accept-Encoding","identity");connection.setRequestProperty("Cookie","");
                int status=connection.getResponseCode();
                long headerChars=0;
                for(java.util.Map.Entry<String,java.util.List<String>> entry:connection.getHeaderFields().entrySet()) {
                    if(entry.getKey()!=null)headerChars+=entry.getKey().length();
                    for(String value:entry.getValue())headerChars+=value.length();
                    if(headerChars>16384)throw new Failure(RuntimeResult.RESPONSE_LIMIT);
                }
                if(status==301 || status==302 || status==303 || status==307 || status==308) {
                    String location=connection.getHeaderField("Location");
                    if(location==null || location.length()>2048)throw new Failure(RuntimeResult.OUT_OF_SCOPE);
                    target=new URI(target).resolve(location).toString();continue;
                }
                if(status!=200)throw new Failure(RuntimeResult.NATIVE_FAILED);
                if("HEAD".equals(method))return new byte[0];
                String content=connection.getContentType(),encoding=connection.getContentEncoding();
                if(content==null || !content.split(";",2)[0].trim().equalsIgnoreCase("text/html")
                        || (encoding!=null && !encoding.equalsIgnoreCase("identity")))throw new Failure(RuntimeResult.NATIVE_FAILED);
                if(connection.getContentLengthLong()>MAX_RESPONSE)throw new Failure(RuntimeResult.RESPONSE_LIMIT);
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];
                try(InputStream in=connection.getInputStream()) {
                    for(;;) {
                        remaining();
                        int allowance=(int)Math.min(buffer.length,Math.min(MAX_RESPONSE-out.size()+1,MAX_TOTAL-bytes+1));
                        if(allowance<=0)throw new Failure(RuntimeResult.RESPONSE_LIMIT);
                        int n=in.read(buffer,0,allowance);if(n<0)break;
                        bytes+=n;callback.network(request.id,requests,bytes);
                        if(out.size()+n>MAX_RESPONSE || bytes>MAX_TOTAL)throw new Failure(RuntimeResult.RESPONSE_LIMIT);
                        out.write(buffer,0,n);
                    }
                }
                return out.toByteArray();
            } catch(Failure e) { throw e; }
            catch(SSLException e) { throw new Failure(RuntimeResult.TLS_ERROR); }
            catch(SocketTimeoutException e) { throw new Failure(RuntimeResult.TIMEOUT); }
            catch(Exception e) { throw new Failure(RuntimeResult.NATIVE_FAILED); }
            finally { if(connection!=null)connection.disconnect(); }
        }
        throw new Failure(RuntimeResult.REQUEST_LIMIT);
    }
    @Override public void close() { io.shutdownNow(); }
}
