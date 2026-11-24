"""Execute the real Java address/URL/peer policy, without network or Android execution claims."""
from pathlib import Path
import subprocess
from copy import deepcopy
import tempfile
import unittest
from rewrite.experiments.android_native.broker_report import inspect, SHA


class NetworkPolicyTest(unittest.TestCase):
    def test_intake_cannot_replace_public_tls_with_fixture_or_accept_missing_cases(self):
        source='d'*40
        records={name:dict(case=name,source_commit=source,api=36,page_size=16384,production_ready=False,product_integration=False)
                 for name in ('address','pinning','tls','cancellation','limits','public')}
        for data in records.values():
            data.update(elapsed_ms=100,process_cpu_delta_ms=10,pss_start_kib=1024,pss_end_kib=2048,
                        measurement_scope='instrumented_process_including_owned_fixture_not_peak')
        records['address'].update(public_policy=True,mixed_rejected=True,peer_rejected=True,connections=0)
        records['pinning'].update(fixture_only=True,single_lookup=True,rebind_rejected=True,redirect_connections=1)
        records['tls'].update(fixture_only=True,wrong_ca_rejected=True,wrong_host_rejected=True,credentials_rejected=True,downgrade_rejected=True)
        records['cancellation'].update(fixture_only=True,body_cancel=True,dns_cancel=True,dns_deadline=True,late_result_refused=True,actual_quiescence_checked=True,dns_threads_bounded=2)
        records['limits'].update(fixture_only=True,body_limit=True,header_limit=True,aggregate_limit=True,request_limit=True,identity_only=True,credentials_absent=True,
                                chunked_refused_before_body=True,unknown_length_refused=True,truncation_refused=True)
        records['public'].update(public_policy=True,system_tls=True,owned_fixture=True,bytes=2428,sha256=SHA,requests=1)
        self.assertEqual(inspect(records,source,36,16384)['tests'],6)
        for change in ('missing','source','fixture_public','tls','sha','connected_private','quiescence','dns_unbounded','redirect','product'):
            broken=deepcopy(records)
            if change=='missing':del broken['tls']
            elif change=='source':broken['public']['source_commit']='e'*40
            elif change=='fixture_public':broken['public']['fixture_only']=True
            elif change=='tls':broken['tls']['wrong_host_rejected']=False
            elif change=='sha':broken['public']['sha256']='0'*64
            elif change=='connected_private':broken['address']['connections']=1
            elif change=='quiescence':broken['cancellation']['actual_quiescence_checked']=False
            elif change=='dns_unbounded':broken['cancellation']['dns_threads_bounded']=3
            elif change=='redirect':broken['pinning']['redirect_connections']=2
            elif change=='product':broken['limits']['product_integration']=True
            with self.subTest(change=change),self.assertRaises(AssertionError):inspect(broken,source,36,16384)
    def test_real_java_public_policy_and_pinned_peer_boundaries(self):
        fixture=r'''
package com.thekain.runtime.lab.network;
import java.net.*;
import java.util.*;
public final class PolicyRegression {
 static void check(boolean value) {if(!value)throw new AssertionError("Policy boundary");}
 static byte[] ip(String value)throws Exception {return InetAddress.getByName(value).getAddress();}
 public static void main(String[] args)throws Exception {
  String[] denied={"0.0.0.0","0.255.255.255","10.0.0.1","10.255.255.255","100.64.0.1","100.127.255.255",
   "127.0.0.1","127.255.255.255","169.254.169.254","172.16.0.1","172.31.255.255","192.0.0.9","192.0.2.1",
   "192.31.196.1","192.52.193.1","192.88.99.1","192.168.1.1","192.175.48.1","198.18.0.1","198.19.255.255",
   "198.51.100.1","203.0.113.1","224.0.0.1","239.255.255.255","240.0.0.1","255.255.255.255","168.63.129.16",
   "::","::1","fe80::1","fc00::1","fd00:ec2::254","ff02::1","64:ff9b::a00:1","2001::1","2001:20::1",
   "2001:db8::1","2002:7f00:1::1","3fff::1","3fff:fff::1","2001:4860::5efe:7f00:1"};
  for(String value:denied)if(PublicHttpsPolicy.isPublic(ip(value)))throw new AssertionError("Unexpected allowed special range");
  for(String value:new String[]{"8.8.8.8","9.255.255.255","11.0.0.1","100.63.255.255","100.128.0.1","172.15.255.255",
    "172.32.0.1","192.169.0.1","198.17.255.255","198.20.0.1","185.199.108.133","2001:200::1","2001:4860:4860::8888","2606:4700:4700::1111","2a04:4e42::1"})
    if(!PublicHttpsPolicy.isPublic(ip(value)))throw new AssertionError("Unexpected denied ordinary range");
  PublicHttpsPolicy policy=new PublicHttpsPolicy(Collections.singleton("https://owned.example.org"));
  PublicHttpsPolicy.Target target=policy.target("https://owned.example.org/video?token=owned");
  InetAddress valid=InetAddress.getByAddress(new byte[]{8,8,8,8});
  PublicHttpsPolicy.Pin pin=policy.pin(target,Collections.singletonList(valid));
  PublicHttpsPolicy.peer(pin,new InetSocketAddress(valid,443));
  for(String url:new String[]{"http://owned.example.org","file:///etc/passwd","https://user:secret@owned.example.org",
    "https://owned.example.org:8443","https://owned.example.org#secret","https://owned.example.org.",
    "https://owned.example.org\\@127.0.0.1","https://owned.example.org.evil.test","https://127.1","https://0177.0.0.1",
    "https://2130706433","https://0x7f.0.0.1","https://[::1]","https://[::ffff:7f00:1]","https://[::ffff:8.8.8.8]",
    "https://[fe80::1%25eth0]","https://metadata.google.internal","https://router.local","https://localhost"}) {
    try {policy.target(url);throw new AssertionError("Unsafe URL accepted");}catch(BrokerFailure expected) {}
  }
  try {policy.pin(target,Arrays.asList(valid,InetAddress.getByAddress(new byte[]{127,0,0,1})));throw new AssertionError("Mixed DNS accepted");}
  catch(BrokerFailure expected) {check(expected.code==BrokerFailure.Code.ADDRESS_DENIED);}
  try {PublicHttpsPolicy.peer(pin,new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),443));throw new AssertionError("Peer changed");}
  catch(BrokerFailure expected) {check(expected.code==BrokerFailure.Code.ADDRESS_DENIED);}
  try {PublicHttpsPolicy.peer(pin,InetSocketAddress.createUnresolved("owned.example.org",443));throw new AssertionError("Unresolved connect");}
  catch(BrokerFailure expected) {}
  try {policy.pin(target,new ArrayList<InetAddress>());throw new AssertionError("Empty DNS accepted");}catch(BrokerFailure expected) {}
  try {policy.pin(target,Collections.nCopies(17,valid));throw new AssertionError("Huge DNS accepted");}catch(BrokerFailure expected) {}
  check(!target.toString().contains("token"));
 }
}
'''
        root=Path(__file__).parent/'android/app/src/debug/java/com/thekain/runtime/lab/network'
        with tempfile.TemporaryDirectory() as directory:
            test=Path(directory)/'PolicyRegression.java';test.write_text(fixture)
            compiled=subprocess.run(['javac','-d',directory,str(root/'BrokerFailure.java'),str(root/'PublicHttpsPolicy.java'),str(test)],capture_output=True,text=True,timeout=30)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            executed=subprocess.run(['java','-cp',directory,'com.thekain.runtime.lab.network.PolicyRegression'],capture_output=True,text=True,timeout=10)
            self.assertEqual(executed.returncode,0,executed.stderr)
