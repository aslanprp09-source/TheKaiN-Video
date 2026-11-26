package com.thekain.runtime.lab.network;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;

/** Trusted Service construction only; no child-controlled grants. All actual IO stays in its PID. */
public final class ServiceBrokerEngine {
    public interface LookupPhase {void started();}
    public static ControlledHttpsBroker create(Set<String> origins,String fixtureOrigin,byte[] ca,int fault,LookupPhase phase) throws Exception {
        ControlledHttpsBroker.Limits limits=ControlledHttpsBroker.Limits.laboratory();
        if(fixtureOrigin==null && fault==0)return new ControlledHttpsBroker(origins,limits);
        ControlledHttpsBroker.Resolver resolver=host->{
            if(fault==1) {phase.started();for(;;)try {new java.util.concurrent.CountDownLatch(1).await();}catch(InterruptedException ignored) { }}
            return fixtureOrigin==null?Arrays.asList(InetAddress.getAllByName(host)):
                Collections.singletonList(InetAddress.getByAddress(new byte[]{127,0,0,1}));
        };
        if(fixtureOrigin==null)return new ControlledHttpsBroker(new PublicHttpsPolicy(origins),resolver,limits,null,null);
        if(ca==null || ca.length==0 || ca.length>16384)throw new IOException("FIXTURE_CA");
        KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);
        store.setCertificateEntry("owned-fixture",CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(ca)));
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
        X509TrustManager manager=null;for(TrustManager value:trust.getTrustManagers())if(value instanceof X509TrustManager)manager=(X509TrustManager)value;
        if(manager==null)throw new IOException("FIXTURE_TRUST");
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{manager},null);
        return new ControlledHttpsBroker(PublicHttpsPolicy.fixture(fixtureOrigin,InetAddress.getByAddress(new byte[]{127,0,0,1})),resolver,limits,tls.getSocketFactory(),manager);
    }
    private ServiceBrokerEngine() { }
}
