"""Fixed JNI smoke protocol, not a public extractor/remote-code API."""
import json
import os
from pathlib import Path
import ssl
import sys
import sysconfig

from spike import MAX_OUTPUT, SpikeError, normalize, origin

_allowed = None


def audit(event,args):
    if event in ('subprocess.Popen','os.system','os.posix_spawn','os.exec','os.fork'):
        raise SpikeError('UNSUPPORTED_MEDIA')
    if event=='socket.connect' and (_allowed is None or args[1]!=_allowed[1:]):
        raise SpikeError('OUT_OF_SCOPE_URL')
    if event=='socket.getaddrinfo' and (_allowed is None or args[:2]!=_allowed[1:]):
        raise SpikeError('OUT_OF_SCOPE_URL')


sys.addaudithook(audit)


def dispatch(raw):
    global _allowed
    try:
        request=json.loads(raw)
        if not isinstance(request,dict): raise ValueError()
        if request.get('op')=='probe':
            result={'status':'PASS','python':list(sys.version_info[:3]),'platform':sys.platform,
                    'openssl':ssl.OPENSSL_VERSION,'page_size':os.sysconf('SC_PAGE_SIZE'),
                    'installed_native_extensions':os.path.dirname(ssl._ssl.__file__)==sys._thekain_native_directory,
                    'mimalloc':int(sysconfig.get_config_var('WITH_MIMALLOC'))}
        elif request.get('op')=='analyze':
            import yt_dlp
            from yt_dlp.version import __version__
            from yt_dlp.globals import plugin_dirs,all_plugins_loaded
            from worker import FixtureNetwork,Quiet
            if __version__!='2026.10.26': raise SpikeError('TOOL_VERSION')
            plugin_dirs.value=[];all_plugins_loaded.value=True
            _allowed=origin(request['url'])
            # Only two known own-fixture CA files; never arbitrary filesystem paths.
            ca=request['ca']
            if ca not in ('owned-ca.pem','wrong-ca.pem'): raise ValueError()
            network=FixtureNetwork(request['url'],str(Path(sys.prefix)/'fixtures'/ca))
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
        else: raise ValueError()
    except Exception as error:
        result={'status':'FAIL','error_code':error.code if isinstance(error,SpikeError) else 'LAB_REQUEST_FAILED'}
    finally: _allowed=None
    return json.dumps(result,ensure_ascii=True,allow_nan=False)
