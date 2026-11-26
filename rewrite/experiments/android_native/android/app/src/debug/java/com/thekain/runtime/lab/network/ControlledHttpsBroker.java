package com.thekain.runtime.lab.network;

import android.os.SystemClock;
import okhttp3.*;
import javax.net.SocketFactory;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.thekain.runtime.lab.network.BrokerFailure.Code.*;

/** Candidate owner-side component only. Not connected to Python IPC or product SourceAnalyzer. */
public final class ControlledHttpsBroker {
    interface Resolver {List<InetAddress> lookup(String host) throws Exception;}
    private static final AtomicInteger DNS_IDS=new AtomicInteger();
    private static final ThreadPoolExecutor DNS=new ThreadPoolExecutor(0,2,1,TimeUnit.SECONDS,
        new SynchronousQueue<>(),task->{Thread thread=new Thread(task,"controlled-dns-"+DNS_IDS.incrementAndGet());thread.setDaemon(true);return thread;});
    public static final class Limits {
        public final long timeoutMs;
        public final int requests,bodyBytes,totalBytes,headerBytes,redirects;
        public Limits(long timeoutMs,int requests,int bodyBytes,int totalBytes,int headerBytes,int redirects) {
            if(timeoutMs<100 || timeoutMs>30000 || requests<1 || requests>12 || bodyBytes<1 || bodyBytes>512*1024
                    || totalBytes<bodyBytes || totalBytes>2*1024*1024 || headerBytes<128 || headerBytes>16384
                    || redirects<0 || redirects>5)throw new IllegalArgumentException("BROKER_LIMITS");
            this.timeoutMs=timeoutMs;this.requests=requests;this.bodyBytes=bodyBytes;this.totalBytes=totalBytes;
            this.headerBytes=headerBytes;this.redirects=redirects;
        }
        public static Limits laboratory() {return new Limits(30000,12,512*1024,2*1024*1024,16384,4);}
    }
    public static final class Reply {
        public final int status,requestNumber;
        public final String finalUrl,contentType;
        public final long declaredBytes;
        private final byte[] bytes;
        Reply(int status,int request,byte[] bytes,String finalUrl,String contentType,long declaredBytes) {
            this.status=status;requestNumber=request;this.bytes=bytes;this.finalUrl=finalUrl;this.contentType=contentType;this.declaredBytes=declaredBytes;
        }
        public byte[] body() {return bytes.clone();}
        @Override public String toString() {return "BrokerReply(status="+status+", bytes="+bytes.length+")";}
    }
    private final PublicHttpsPolicy policy;
    private final Resolver resolver;
    private final Limits limits;
    private final SSLSocketFactory fixtureTls;
    private final X509TrustManager fixtureTrust;
    public ControlledHttpsBroker(Set<String> origins,Limits limits) throws BrokerFailure {
        this(new PublicHttpsPolicy(origins),host->Arrays.asList(InetAddress.getAllByName(host)),limits,null,null);
    }
    // Only instrumentation in this package can select an owned loopback scope/CA.
    ControlledHttpsBroker(PublicHttpsPolicy policy,Resolver resolver,Limits limits,SSLSocketFactory tls,X509TrustManager trust) {
        if(policy==null || resolver==null || limits==null || (tls==null)!=(trust==null))throw new IllegalArgumentException("BROKER_CONFIG");
        this.policy=policy;this.resolver=resolver;this.limits=limits;fixtureTls=tls;fixtureTrust=trust;
    }
    public Operation operation() {return new Operation(SystemClock.elapsedRealtime()+limits.timeoutMs);}
    public Operation operationUntil(long absoluteDeadline) throws BrokerFailure {
        long now=SystemClock.elapsedRealtime();if(absoluteDeadline<=now)throw new BrokerFailure(TIMEOUT);
        return new Operation(Math.min(absoluteDeadline,now+limits.timeoutMs));
    }
    public final class Operation implements AutoCloseable {
        private final long deadline;
        private Operation(long deadline) {this.deadline=deadline;}
        private final AtomicBoolean canceled=new AtomicBoolean(),fetching=new AtomicBoolean();
        private final AtomicReference<Call> call=new AtomicReference<>();
        private final Set<Socket> sockets=Collections.synchronizedSet(new HashSet<>());
        private volatile Lookup lookup;
        private volatile int requests,payloadBytes,connections;
        private final class Lookup {
            final CompletableFuture<List<InetAddress>> result=new CompletableFuture<>();
            volatile boolean done;volatile Thread thread;
            synchronized void interruptIfActive() {if(!done && thread!=null)thread.interrupt();}
        }
        private int remaining() throws BrokerFailure {
            if(Thread.currentThread().isInterrupted())canceled.set(true);
            if(canceled.get())throw new BrokerFailure(CANCELED);
            long left=deadline-SystemClock.elapsedRealtime();if(left<=0)throw new BrokerFailure(TIMEOUT);
            return (int)left;
        }
        public int requestCount() {return requests;}
        public int payloadBytesRead() {return payloadBytes;}
        public int connectionAttempts() {return connections;}
        private synchronized Reply commit(Response response,PublicHttpsPolicy.Target target,byte[] bytes) throws BrokerFailure {
            String type=response.header("Content-Type");
            if(response.headers("Content-Type").size()>1 || type!=null && (type.length()>128 || type.chars().anyMatch(c->c<32 || c>126)))throw new BrokerFailure(HEADER_LIMIT);
            long declared=-1;String length=response.header("Content-Length");
            if(length!=null && length.matches("[0-9]{1,19}"))try {declared=Long.parseLong(length);}catch(NumberFormatException ignored) { }
            remaining();return new Reply(response.code(),requests,bytes,response.request().url().toString(),type,declared);
        }
        /** Actual owned lookup/network work ended; Future cancellation alone grants no quiescence. */
        public boolean isQuiescent() {Lookup value=lookup;return !fetching.get() && call.get()==null && sockets.isEmpty() && (value==null || value.done);}
        @Override public void close() {
            synchronized(this) {canceled.set(true);}
            Call current=call.get();if(current!=null)current.cancel();
            Lookup value=lookup;if(value!=null)value.interruptIfActive();
            Socket[] owned;synchronized(sockets) {owned=sockets.toArray(new Socket[0]);}
            for(Socket socket:owned)try {socket.close();}catch(IOException ignored) { }
        }
        private PublicHttpsPolicy.Pin resolve(PublicHttpsPolicy.Target target) throws BrokerFailure {
            remaining();
            if(target.literal!=null)try {return policy.pin(target,Collections.singletonList(InetAddress.getByAddress(target.literal)));}
            catch(UnknownHostException invalid) {throw new BrokerFailure(ADDRESS_DENIED);}
            Lookup query=new Lookup();lookup=query;
            try {
                DNS.execute(()->{
                    synchronized(query) {query.thread=Thread.currentThread();}
                    List<InetAddress> result=null;Exception error=null;
                    try {remaining();result=resolver.lookup(target.host);}catch(Exception failed) {error=failed;}
                    finally {synchronized(query) {query.done=true;}}
                    // Private promise: no user continuation or shared completion executor.
                    if(error==null)query.result.complete(result);else query.result.completeExceptionally(error);
                });
            }catch(RejectedExecutionException full) {query.done=true;throw new BrokerFailure(DNS_BUSY);}
            while(true) {
                int left=remaining();
                try {List<InetAddress> answers=query.result.get(Math.min(25,left),TimeUnit.MILLISECONDS);remaining();return policy.pin(target,answers);}
                catch(TimeoutException waiting) { /* Same global budget, never an extended lookup deadline. */ }
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();close();throw new BrokerFailure(CANCELED);}
                catch(ExecutionException failed) {remaining();throw new BrokerFailure(DNS_FAILED);}
            }
        }
        public Reply fetch(String input,String method) throws BrokerFailure {
            if(!fetching.compareAndSet(false,true))throw new BrokerFailure(BUSY);
            try {
                remaining();
                if(!"GET".equals(method) && !"HEAD".equals(method)) {
                    if(requests>=limits.requests)throw new BrokerFailure(REQUEST_LIMIT);requests++;
                    throw new BrokerFailure(INVALID_URL);
                }
                String address=input;int redirects=0;
                while(true) {
                    remaining();if(requests>=limits.requests)throw new BrokerFailure(REQUEST_LIMIT);requests++;
                    PublicHttpsPolicy.Target target=policy.target(address);
                    PublicHttpsPolicy.Pin pin=resolve(target);remaining();
                    HttpUrl url;
                    try {url=HttpUrl.get(target.uri.toASCIIString());}catch(IllegalArgumentException invalid) {throw new BrokerFailure(INVALID_URL);}
                    if(!url.scheme().equals("https") || !url.username().isEmpty() || !url.password().isEmpty() || url.port()!=target.port)
                        throw new BrokerFailure(INVALID_URL);
                    if(target.literal==null && !url.host().equals(target.host))throw new BrokerFailure(INVALID_URL);
                    OkHttpClient.Builder builder=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
                        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE).cache(null)
                        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                        .protocols(Collections.singletonList(Protocol.HTTP_1_1)).connectionPool(new ConnectionPool(0,1,TimeUnit.SECONDS))
                        .socketFactory(new GuardedSockets(pin)).dns(host->{
                            if(!host.equals(url.host()))throw new UnknownHostException("PIN_SCOPE");
                            return Collections.singletonList(pin.address);
                        }).callTimeout(remaining(),TimeUnit.MILLISECONDS)
                        .connectTimeout(Math.min(3000,remaining()),TimeUnit.MILLISECONDS).readTimeout(Math.min(3000,remaining()),TimeUnit.MILLISECONDS);
                    if(fixtureTls!=null)builder.sslSocketFactory(fixtureTls,fixtureTrust);
                    OkHttpClient client=builder.build();Call current=client.newCall(new Request.Builder().url(url)
                        .method(method,null).header("User-Agent","TheKaiN-controlled-https-lab")
                        .header("Accept-Encoding","identity").header("Connection","close").build());
                    call.set(current);Response response=null;
                    try {
                        remaining();response=current.execute();remaining();
                        if(response.headers().byteCount()>limits.headerBytes)throw new BrokerFailure(HEADER_LIMIT);
                        int status=response.code();
                        if(status==301 || status==302 || status==303 || status==307 || status==308) {
                            String location=response.header("Location");
                            if(location==null || location.length()>2048)throw new BrokerFailure(INVALID_URL);
                            if(redirects++>=limits.redirects)throw new BrokerFailure(REQUEST_LIMIT);
                            try {address=target.uri.resolve(location).toString();}catch(IllegalArgumentException invalid) {throw new BrokerFailure(INVALID_URL);}
                            policy.target(address); // Deny changed scope/downgrade before another DNS/connect.
                            continue;
                        }
                        if(status!=200)throw new BrokerFailure(HTTP_ERROR,status);
                        List<String> encodings=response.headers("Content-Encoding");
                        if(encodings.size()>1 || encodings.size()==1 && !encodings.get(0).equalsIgnoreCase("identity"))
                            throw new BrokerFailure(UNSUPPORTED_ENCODING);
                        if(method.equals("HEAD"))return commit(response,target,new byte[0]);
                        ResponseBody body=response.body();if(body==null)throw new BrokerFailure(NETWORK_LOST);
                        // This pinned HTTP/1 codec has unbounded chunk-extension line parsing.
                        // Do not enter that parser; unknown/ambiguous framing is a typed refusal.
                        List<String> lengths=response.headers("Content-Length");
                        if(response.header("Transfer-Encoding")!=null || lengths.size()!=1 || !lengths.get(0).matches("[0-9]{1,19}")
                                || body.contentLength()<0)throw new BrokerFailure(UNSUPPORTED_FRAMING);
                        if(body.contentLength()>Math.min(limits.bodyBytes,limits.totalBytes-payloadBytes))throw new BrokerFailure(RESPONSE_LIMIT);
                        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];
                        InputStream in=body.byteStream();
                        while(true) {
                            remaining();int allowance=Math.min(limits.bodyBytes-out.size(),limits.totalBytes-payloadBytes);
                            if(allowance<0)throw new BrokerFailure(RESPONSE_LIMIT);
                            int count=in.read(buffer,0,Math.min(buffer.length,allowance+1));
                            if(count>0)payloadBytes+=count; // Count bytes even if cancellation/deadline wins immediately after this read.
                            remaining();if(count<0)break;
                            if(count>allowance)throw new BrokerFailure(RESPONSE_LIMIT);out.write(buffer,0,count);
                        }
                        return commit(response,target,out.toByteArray());
                    }catch(BrokerFailure failure) {throw failure;}
                    catch(SSLException tls) {remaining();throw new BrokerFailure(TLS_ERROR);}
                    catch(InterruptedIOException timeout) {remaining();throw new BrokerFailure(TIMEOUT);}
                    catch(IOException network) {remaining();throw new BrokerFailure(NETWORK_LOST);}
                    finally {
                        // Cancel before close: no hidden drain of a redirect/oversized response body.
                        current.cancel();if(response!=null)response.close();client.connectionPool().evictAll();call.compareAndSet(current,null);
                    }
                }
            }catch(SecurityException denied) {throw new BrokerFailure(ADDRESS_DENIED);}
            finally {fetching.set(false);}
        }
        private final class GuardedSockets extends SocketFactory {
            private final PublicHttpsPolicy.Pin pin;
            GuardedSockets(PublicHttpsPolicy.Pin pin) {this.pin=pin;}
            @Override public Socket createSocket() {return new Socket() {
                {sockets.add(this);}
                @Override public void connect(SocketAddress endpoint,int timeout) throws IOException {
                    try {
                        remaining();PublicHttpsPolicy.peer(pin,endpoint);connections++;super.connect(endpoint,timeout);
                        PublicHttpsPolicy.peer(pin,getRemoteSocketAddress());remaining();
                    }catch(IOException rejected) {close();throw rejected;}
                }
                @Override public void connect(SocketAddress endpoint) throws IOException {connect(endpoint,Math.min(3000,remaining()));}
                @Override public synchronized void close() throws IOException {try {super.close();}finally {sockets.remove(this);}}
            };}
            @Override public Socket createSocket(String host,int port) throws IOException {throw new BrokerFailure(ADDRESS_DENIED);}
            @Override public Socket createSocket(String host,int port,InetAddress local,int localPort) throws IOException {throw new BrokerFailure(ADDRESS_DENIED);}
            @Override public Socket createSocket(InetAddress host,int port) throws IOException {throw new BrokerFailure(ADDRESS_DENIED);}
            @Override public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort) throws IOException {throw new BrokerFailure(ADDRESS_DENIED);}
        }
    }
}
