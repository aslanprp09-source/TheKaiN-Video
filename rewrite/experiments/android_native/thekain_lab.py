"""Fixed JNI smoke protocol, not a public extractor/remote-code API."""
import json
import os
from pathlib import Path
import ssl
import sys
import sysconfig
import struct

from spike import MAX_OUTPUT, SpikeError, normalize, origin
import _thekain_transport

_calls = 0

_allowed = None


def audit(event,args):
    if event in ('subprocess.Popen','os.system','os.posix_spawn','os.exec','os.fork'):
        raise SpikeError('UNSUPPORTED_MEDIA')
    if event in ('socket.__new__','socket.connect','socket.getaddrinfo'):
        raise SpikeError('OUT_OF_SCOPE_URL')


sys.addaudithook(audit)


def openssl_memory_handshake():
    """Real OpenSSL client/server handshake without granting Python a socket."""
    if hasattr(_thekain_transport,'memory_tls'):
        return _thekain_transport.memory_tls(ssl.OPENSSL_VERSION)
    root=Path(sys.prefix)/'fixtures'
    server=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    certificate=getattr(sys,'_thekain_fixture_cert',root/'owned-ca.pem')
    key=getattr(sys,'_thekain_fixture_key',root/'owned-key.pem')
    server.load_cert_chain(certificate,key)
    client=ssl.create_default_context(cafile=certificate)
    ci,co,si,so=(ssl.MemoryBIO() for _ in range(4))
    peers=(client.wrap_bio(ci,co,server_hostname='127.0.0.1'),server.wrap_bio(si,so,server_side=True))
    done=[False,False]
    for _ in range(64):
        for i,peer in enumerate(peers):
            if not done[i]:
                try: peer.do_handshake();done[i]=True
                except (ssl.SSLWantReadError,ssl.SSLWantWriteError): pass
        for outgoing,incoming in ((co,si),(so,ci)):
            data=outgoing.read()
            if data: incoming.write(data)
        if all(done): return peers[0].version() in ('TLSv1.2','TLSv1.3')
    raise RuntimeError('OWNED_MEMORY_TLS_HANDSHAKE_FAILED')


def dispatch(raw):
    global _allowed, _calls
    _calls += 1
    _thekain_transport.started()
    try:
        request=json.loads(raw)
        if not isinstance(request,dict): raise ValueError()
        if request.get('op')=='probe':
            result={'status':'PASS','python':list(sys.version_info[:3]),'platform':sys.platform,
                    'openssl':ssl.OPENSSL_VERSION,'page_size':os.sysconf('SC_PAGE_SIZE'),
                    'machine':os.uname().machine,'pointer_bits':struct.calcsize('P')*8,
                    'installed_native_extensions':os.path.dirname(ssl._ssl.__file__)==sys._thekain_native_directory,
                    'mimalloc':int(sysconfig.get_config_var('WITH_MIMALLOC')),
                    'openssl_handshake':openssl_memory_handshake()}
        elif request.get('op')=='source_analyze':
            from source_analysis import analyze
            result=analyze(request['url'])
            result.update(python=list(sys.version_info[:3]),page_size=os.sysconf('SC_PAGE_SIZE'),machine=os.uname().machine,pointer_bits=struct.calcsize('P')*8)
        elif request.get('op')=='analyze':
            import yt_dlp
            from yt_dlp.version import __version__
            from yt_dlp.globals import plugin_dirs,all_plugins_loaded
            from bounded_transport import FixtureNetwork
            from worker import Quiet
            if __version__!='2026.10.26': raise SpikeError('TOOL_VERSION')
            plugin_dirs.value=[];all_plugins_loaded.value=True
            _allowed=origin(request['url'])
            network=FixtureNetwork()
            class FixtureYDL(yt_dlp.YoutubeDL):
                def urlopen(self,req): return network.open(req)
            with FixtureYDL({'quiet':True,'no_warnings':True,'logger':Quiet(),'cachedir':False,
                    'skip_download':True,'noplaylist':True,'format':'best[acodec!=none][vcodec!=none]',
                    'allowed_extractors':['generic','html5'],'js_runtimes':{},'remote_components':[],
                    'retries':0,'extractor_retries':0,'postprocessors':[],
                    'force_generic_extractor':True,'ignore_no_formats_error':True}) as ydl:
                info=ydl.sanitize_info(ydl.extract_info(request['url'],download=False))
            if len(json.dumps(info,ensure_ascii=True,allow_nan=False).encode())>MAX_OUTPUT:
                raise SpikeError('OUTPUT_LIMIT')
            analysis=normalize(info,_allowed)
            result={'status':'PASS','formats':len(analysis.candidates),'download_enabled':analysis.download_enabled,
                    'video_codec':analysis.candidates[0].video_codec,'audio_codec':analysis.candidates[0].audio_codec,
                    'eligible':analysis.candidates[0].transfer_eligible,'reason':analysis.candidates[0].blocked_reason}
        elif request.get('op')=='block_exec':
            import subprocess
            try: subprocess.run(['/system/bin/true'],check=True)
            except SpikeError: result={'status':'PASS','external_exec_blocked':True}
            else: raise RuntimeError()
        elif request.get('op')=='hang':
            while True: pass
        elif request.get('op')=='crash':
            os.abort()
        elif request.get('op')=='memory':
            import time
            chunks=[]
            try:
                while True:
                    chunk=bytearray(32*1024*1024)
                    chunk[::4096]=b'x'*(len(chunk)//4096)
                    chunks.append(chunk)
                    time.sleep(.02)
            except MemoryError:
                del chunks
                result={'status':'FAIL','error_code':'MEMORY_LIMIT'}
        elif request.get('op')=='malformed_channel':
            _thekain_transport.malformed()  # Fixed lab-only oversized frame; never arbitrary code.
            raise RuntimeError('MALFORMED_CHILD_FRAME_ACCEPTED')
        elif request.get('op')=='output_flood':
            return 'x'*70000
        elif request.get('op') in ('request_flood','byte_flood'):
            from bounded_transport import FixtureNetwork
            network=FixtureNetwork()
            for _ in range(13): network.open(request['url'])
            raise RuntimeError('JAVA_REQUEST_BUDGET_WAS_NOT_ENFORCED')
        elif request.get('op')=='isolated_security':
            import errno
            denied=0
            canary=Path(request['owner_canary'])
            for bit,mode in ((1,'rb'),(2,'ab')):
                try:
                    with canary.open(mode): pass
                except (PermissionError,FileNotFoundError): denied |= bit
            # posix.open is a real kernel path open, not an audit-hook simulation.
            if denied!=3: raise RuntimeError('OWNER_FILES_ACCESSIBLE')
            import _socket
            try: _socket.socket()
            except (SpikeError,PermissionError): denied |= 4
            result={'status':'PASS','security_denials':denied}
        elif request.get('op')=='direct_socket':
            import socket
            try: socket.socket()
            except (SpikeError,PermissionError): result={'status':'PASS','external_exec_blocked':True}
            else: raise RuntimeError('DIRECT_NETWORK_NOT_BLOCKED')
        else: raise ValueError()
    except Exception as error:
        result={'status':'FAIL','error_code':error.code if isinstance(error,SpikeError) else 'LAB_REQUEST_FAILED'}
    finally: _allowed=None
    result['python_calls']=_calls
    return json.dumps(result,ensure_ascii=True,allow_nan=False)
