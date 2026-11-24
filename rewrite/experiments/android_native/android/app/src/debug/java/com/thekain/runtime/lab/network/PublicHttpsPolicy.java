package com.thekain.runtime.lab.network;

import java.net.*;
import java.util.*;
import static com.thekain.runtime.lab.network.BrokerFailure.Code.*;

/** Conservative public-unicast policy. It never resolves a hostname during parsing. */
public final class PublicHttpsPolicy {
    private static final int MAX_URL=2048,MAX_ANSWERS=16;
    private static final String[] V4_DENY={"0.0.0.0/8","10.0.0.0/8","100.64.0.0/10","127.0.0.0/8",
        "169.254.0.0/16","172.16.0.0/12","192.0.0.0/24","192.0.2.0/24","192.31.196.0/24",
        "192.52.193.0/24","192.88.99.0/24","192.168.0.0/16","192.175.48.0/24","198.18.0.0/15",
        "198.51.100.0/24","203.0.113.0/24","224.0.0.0/4","240.0.0.0/4","168.63.129.16/32"};
    private static final byte[][] V4_NETWORKS;
    private static final int[] V4_PREFIXES;
    static {
        V4_NETWORKS=new byte[V4_DENY.length][];V4_PREFIXES=new int[V4_DENY.length];
        for(int i=0;i<V4_DENY.length;i++) {
            String[] parts=V4_DENY[i].split("/");V4_NETWORKS[i]=ipv4(parts[0]);V4_PREFIXES[i]=Integer.parseInt(parts[1]);
        }
    }
    private final Set<String> origins;
    private final byte[] fixtureAddress;

    public PublicHttpsPolicy(Set<String> origins) throws BrokerFailure {this(origins,null);}
    private PublicHttpsPolicy(Set<String> input,byte[] fixtureAddress) throws BrokerFailure {
        if(input==null || input.isEmpty() || input.size()>8)throw new BrokerFailure(OUT_OF_SCOPE);
        this.fixtureAddress=fixtureAddress==null?null:fixtureAddress.clone();
        Set<String> normalized=new HashSet<>();
        for(String value:input) {
            Target target=parse(value,fixtureAddress!=null);
            if(target.uri.getRawQuery()!=null || !(target.uri.getRawPath().isEmpty() || target.uri.getRawPath().equals("/")))
                throw new BrokerFailure(INVALID_URL);
            normalized.add(target.origin);
        }
        origins=Collections.unmodifiableSet(normalized);
    }
    /** Instrumentation-only capability for exactly one owned loopback TLS endpoint. Not a public-policy pass. */
    static PublicHttpsPolicy fixture(String origin,InetAddress address) throws BrokerFailure {
        if(address==null || !address.isLoopbackAddress())throw new BrokerFailure(ADDRESS_DENIED);
        return new PublicHttpsPolicy(Collections.singleton(origin),address.getAddress());
    }
    public static final class Target {
        final URI uri;final String host;final int port;final byte[] literal;
        public final String origin;
        Target(URI uri,String host,int port,byte[] literal) {
            this.uri=uri;this.host=host;this.port=port;this.literal=literal;
            origin="https://"+(host.contains(":")?"["+host+"]":host)+":"+port;
        }
        @Override public String toString() {return "HttpsTarget";}
    }
    static final class Pin {
        final Target target;final InetAddress address;
        Pin(Target target,InetAddress address) {this.target=target;this.address=address;}
    }
    public Target target(String value) throws BrokerFailure {
        Target target=parse(value,fixtureAddress!=null);
        if(!origins.contains(target.origin))throw new BrokerFailure(OUT_OF_SCOPE);
        return target;
    }
    private static Target parse(String value,boolean fixture) throws BrokerFailure {
        if(value==null || value.length()==0 || value.length()>MAX_URL || value.indexOf('\\')>=0)
            throw new BrokerFailure(INVALID_URL);
        for(int i=0;i<value.length();i++)if(value.charAt(i)<=32 || value.charAt(i)==127)throw new BrokerFailure(INVALID_URL);
        try {
            URI uri=new URI(value);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null
                    || uri.getHost()==null || uri.getRawAuthority()==null || uri.getRawAuthority().contains("%"))
                throw new BrokerFailure(INVALID_URL);
            String host=uri.getHost().toLowerCase(Locale.ROOT);
            if(host.startsWith("[") && host.endsWith("]"))host=host.substring(1,host.length()-1);
            if(host.length()>253 || host.endsWith("."))throw new BrokerFailure(INVALID_URL);
            int port=uri.getPort()==-1?443:uri.getPort();
            if(port<1 || port>65535 || (!fixture && port!=443))throw new BrokerFailure(OUT_OF_SCOPE);
            byte[] literal=null;
            if(host.contains(":")) {
                if(!host.matches("[0-9a-f:]+") || !host.contains("::") && host.chars().filter(c->c==':').count()!=7)
                    throw new BrokerFailure(INVALID_URL);
                // Brackets and the restricted alphabet force numeric IPv6 parsing, never DNS.
                literal=InetAddress.getByName("["+host+"]").getAddress();
                if(literal.length!=16)throw new BrokerFailure(ADDRESS_DENIED);
                host=InetAddress.getByAddress(literal).getHostAddress().toLowerCase(Locale.ROOT);
            } else if(host.matches("[0-9.]+")) {
                literal=ipv4(host);if(literal==null)throw new BrokerFailure(INVALID_URL);
            } else {
                if(!host.contains(".") || !host.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")
                        || host.substring(host.lastIndexOf('.')+1).matches("[0-9]+"))throw new BrokerFailure(INVALID_URL);
                for(String label:host.split("\\."))if(label.length()>63 || label.startsWith("-") || label.endsWith("-"))
                    throw new BrokerFailure(INVALID_URL);
                if(!fixture)for(String suffix:new String[]{".localhost",".local",".internal",".home.arpa",".onion",".test",".invalid",".example"})
                    if(host.endsWith(suffix) || host.equals(suffix.substring(1)))throw new BrokerFailure(ADDRESS_DENIED);
            }
            if(!fixture && literal!=null && !isPublic(literal))throw new BrokerFailure(ADDRESS_DENIED);
            return new Target(uri,host,port,literal);
        }catch(BrokerFailure failure) {throw failure;}
        catch(Exception invalid) {throw new BrokerFailure(INVALID_URL);}
    }
    Pin pin(Target target,List<InetAddress> answers) throws BrokerFailure {
        if(answers==null || answers.isEmpty() || answers.size()>MAX_ANSWERS)throw new BrokerFailure(DNS_FAILED);
        List<InetAddress> checked=new ArrayList<>();
        for(InetAddress address:answers) {
            if(address==null || (fixtureAddress==null?!isPublic(address.getAddress()):!Arrays.equals(fixtureAddress,address.getAddress())))
                throw new BrokerFailure(ADDRESS_DENIED); // Reject the complete mixed answer, do not filter/fall back.
            if(target.literal!=null && !Arrays.equals(target.literal,address.getAddress()))throw new BrokerFailure(ADDRESS_DENIED);
            if(!checked.contains(address))checked.add(address);
        }
        // One explicit pinned peer; no implicit route retry or second DNS lookup. IPv4 first for Android NAT.
        checked.sort(Comparator.comparingInt(a->a.getAddress().length));
        return new Pin(target,checked.get(0));
    }
    static void peer(Pin pin,SocketAddress endpoint) throws BrokerFailure {
        if(!(endpoint instanceof InetSocketAddress))throw new BrokerFailure(ADDRESS_DENIED);
        InetSocketAddress target=(InetSocketAddress)endpoint;
        if(target.isUnresolved() || target.getPort()!=pin.target.port || !Arrays.equals(target.getAddress().getAddress(),pin.address.getAddress()))
            throw new BrokerFailure(ADDRESS_DENIED);
    }
    public static boolean isPublic(byte[] address) {
        if(address==null)return false;
        if(address.length==4) {
            for(int i=0;i<V4_NETWORKS.length;i++)if(prefix(address,V4_NETWORKS[i],V4_PREFIXES[i]))return false;
            return true;
        }
        if(address.length!=16 || (address[0]&0xe0)!=0x20)return false;
        if((address[0]&255)==0x20 && (address[1]&255)==0x01 && (address[2]&0xfe)==0)return false; // 2001::/23
        if((address[0]&255)==0x20 && (address[1]&255)==0x01 && (address[2]&255)==0x0d && (address[3]&255)==0xb8)return false;
        if((address[0]&255)==0x20 && (address[1]&255)==0x02)return false; // 6to4
        if((address[0]&255)==0x3f && (address[1]&255)==0xff && (address[2]&0xf0)==0)return false; // 3fff::/20
        if(((address[8]&255)==0 || (address[8]&255)==2) && address[9]==0 && (address[10]&255)==0x5e && (address[11]&255)==0xfe)return false;
        return true;
    }
    private static boolean prefix(byte[] address,byte[] network,int bits) {
        for(int i=0;i<address.length && bits>0;i++,bits-=8) {
            int mask=(0xff << Math.max(0,8-bits))&0xff;
            if((address[i]&mask)!=(network[i]&mask))return false;
        }
        return true;
    }
    private static byte[] ipv4(String host) {
        String[] parts=host.split("\\.",-1);if(parts.length!=4)return null;byte[] result=new byte[4];
        for(int i=0;i<4;i++) {
            if(!parts[i].matches("0|[1-9][0-9]{0,2}"))return null;
            int value=Integer.parseInt(parts[i]);if(value>255)return null;result[i]=(byte)value;
        }
        return result;
    }
}
