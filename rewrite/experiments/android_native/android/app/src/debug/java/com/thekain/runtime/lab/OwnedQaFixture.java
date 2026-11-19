package com.thekain.runtime.lab;

import android.content.Context;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed owned HTML, ephemeral per-session key, numeric loopback, no media/filesystem routes. */
final class OwnedQaFixture implements AutoCloseable {
    private final SSLServerSocket server;
    private final ExecutorService transport=Executors.newSingleThreadExecutor();
    final AtomicInteger requests=new AtomicInteger();
    final String url;
    private volatile boolean closed;
    OwnedQaFixture(Context context) throws Exception {
        File home=RuntimeAssets.prepare(context),folder=new File(home,"fixtures");
        if(!folder.mkdirs() && !folder.isDirectory())throw new IOException("FIXTURE_DIRECTORY");
        HeldCertificate cert=new HeldCertificate.Builder().commonName("owned QA fixture")
            .addSubjectAlternativeName("127.0.0.1").build();
        HeldCertificate wrong=new HeldCertificate.Builder().commonName("unrelated QA fixture").build();
        write(new File(folder,"owned-ca.pem"),cert.certificatePem());
        write(new File(folder,"owned-key.pem"),cert.privateKeyPkcs8Pem());
        write(new File(folder,"wrong-ca.pem"),wrong.certificatePem());
        HandshakeCertificates identity=new HandshakeCertificates.Builder().heldCertificate(cert).build();
        server=(SSLServerSocket)identity.sslContext().getServerSocketFactory().createServerSocket(
            0,2,InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(1000);url="https://127.0.0.1:"+server.getLocalPort()+"/fixture";
        transport.execute(this::serve);
        HandshakeCertificates trust=new HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate()).build();
        try(Socket tcp=new Socket()) {
            tcp.connect(new InetSocketAddress("127.0.0.1",server.getLocalPort()),5000);
            try(SSLSocket tls=(SSLSocket)trust.sslSocketFactory().createSocket(tcp,"127.0.0.1",server.getLocalPort(),true)) {
                tls.setSoTimeout(5000);SSLParameters parameters=tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);tls.startHandshake();
            }
        }
    }
    private static void write(File file,String data) throws IOException {
        try(FileOutputStream out=new FileOutputStream(file)) {out.write(data.getBytes(StandardCharsets.US_ASCII));}
    }
    private void serve() {
        while(!closed) {
            try(SSLSocket socket=(SSLSocket)server.accept()) {
                socket.setSoTimeout(3000);socket.startHandshake();
                InputStream in=socket.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();
                int last=0;
                while(header.size()<16384) {
                    int value=in.read();if(value<0)break;header.write(value);
                    last=(last<<8)|value;if(last==0x0d0a0d0a)break;
                }
                if(last!=0x0d0a0d0a)continue; // No partial or oversized HTTP headers.
                String text=header.toString("US-ASCII");
                boolean get=text.startsWith("GET /fixture HTTP/1.1\r\n"),head=text.startsWith("HEAD /fixture HTTP/1.1\r\n");
                requests.incrementAndGet();
                byte[] body=(get||head?"<html><title>Owned ARM64 QA</title><video><source src='/owned.mp4' type='video/mp4; codecs=\"avc1.42E01E, mp4a.40.2\"'></video></html>":"owned fixture only").getBytes(StandardCharsets.US_ASCII);
                String response="HTTP/1.1 "+(get||head?"200 OK":"404 Not Found")+"\r\nContent-Type: text/html\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";
                OutputStream out=socket.getOutputStream();out.write(response.getBytes(StandardCharsets.US_ASCII));if(!head)out.write(body);out.flush();
            } catch(IOException expected) { /* bounded readiness/peer-close/timeout; no request data logged */ }
        }
    }
    @Override public void close() throws IOException {closed=true;server.close();transport.shutdownNow();}
}
