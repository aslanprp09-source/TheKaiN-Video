"""Fixed generic/HTML5 source analysis over owner-approved HTTPS metadata. No sockets/JS/plugins/downloads."""
import io
import json
import math
import re
from urllib.parse import urlsplit
from spike import SpikeError, MAX_OUTPUT


def scope(url):
    try:
        if not isinstance(url,str) or not 0<len(url)<=2048 or '\\' in url or any(ord(c)<33 or ord(c)>126 for c in url):raise ValueError()
        parsed=urlsplit(url)
        if parsed.scheme!='https' or not parsed.hostname or parsed.username is not None or parsed.password is not None or parsed.fragment:raise ValueError()
        return parsed.hostname,parsed.port or 443
    except (TypeError,ValueError):raise SpikeError('INVALID_URL') from None


def normalize(info,url):
    allowed=scope(url)
    if not isinstance(info,dict) or info.get('_type','video')!='video' or 'entries' in info or info.get('has_drm') or info.get('is_live'):
        raise SpikeError('UNSUPPORTED_MEDIA')
    values=info.get('formats')
    if not isinstance(values,list) or len(values)>128:raise SpikeError('INVALID_METADATA')
    candidates=[];ids=set()
    for value in values:
        if not isinstance(value,dict):raise SpikeError('INVALID_METADATA')
        if value.get('has_drm') or value.get('protocol')!='https' or value.get('ext')!='mp4' or value.get('fragments') or value.get('manifest_url'):
            continue
        if value.get('vcodec')=='none' or value.get('acodec')=='none':continue
        if scope(value.get('url'))!=allowed:raise SpikeError('OUT_OF_SCOPE_URL')
        headers=value.get('http_headers',{})
        if not isinstance(headers,dict) or any(not isinstance(k,str) or k.lower() not in ('user-agent','accept','accept-language','sec-fetch-mode','referer') for k in headers):
            raise SpikeError('UNSUPPORTED_MEDIA')
        identifier=value.get('format_id')
        if not isinstance(identifier,str) or not re.fullmatch('[A-Za-z0-9_.:+-]{1,64}',identifier) or identifier in ids:raise SpikeError('INVALID_METADATA')
        ids.add(identifier)
        item=dict(id=identifier,url=value['url'],container='mp4')
        for source,target in (('filesize','bytes'),('width','width'),('height','height')):
            number=value.get(source)
            if number is not None and (type(number) is not int or number<=0 or number> (2**63-1 if source=='filesize' else 16384)):
                raise SpikeError('INVALID_METADATA')
            item[target]=number
        fps=value.get('fps')
        if fps is not None and (type(fps) not in (int,float) or not math.isfinite(fps) or not 0<fps<=1000):raise SpikeError('INVALID_METADATA')
        item['fps']=fps
        for source,target in (('vcodec','video_codec'),('acodec','audio_codec')):
            codec=value.get(source)
            item[target]=None if codec in (None,'','unknown') else codec
            if item[target] is not None and (not isinstance(codec,str) or not re.fullmatch('[A-Za-z0-9_.:+-]{1,64}',codec)):raise SpikeError('INVALID_METADATA')
        candidates.append(item)
        if len(candidates)>8:raise SpikeError('OUTPUT_LIMIT')
    if not candidates:raise SpikeError('UNSUPPORTED_MEDIA')
    title=info.get('title')
    if not isinstance(title,str):raise SpikeError('INVALID_METADATA')
    title=' '.join(title.split())[:320]
    if not title:raise SpikeError('INVALID_METADATA')
    characters=len(title)+len(allowed[0])+sum(len(str(v)) for f in candidates for v in (f['id'],f['url'],f['container'],f['video_codec'] or '',f['audio_codec'] or ''))
    if characters>4096:raise SpikeError('OUTPUT_LIMIT')
    return dict(title=title,host=allowed[0],formats=candidates)


class ApprovedNetwork:
    def open(self,request):
        import _thekain_transport
        from yt_dlp.networking.common import Response
        url=request if isinstance(request,str) else request.url
        method='GET' if isinstance(request,str) else request.method
        headers={} if isinstance(request,str) else request.headers
        if method not in ('GET','HEAD') or any(k.lower() in ('authorization','cookie','proxy-authorization') for k in headers):raise SpikeError('UNSUPPORTED_MEDIA')
        try:body,final_url,content_type,status,length=_thekain_transport.fetch_info(url,method)
        except _thekain_transport.Error as error:raise SpikeError(str(error)) from None
        response_headers={}
        if content_type:response_headers['Content-Type']=content_type
        if length>=0:response_headers['Content-Length']=str(length)
        return Response(io.BytesIO(body),final_url,response_headers,status)


def analyze(url):
    import yt_dlp
    from yt_dlp.version import __version__
    from yt_dlp.globals import plugin_dirs,all_plugins_loaded
    from worker import Quiet
    if __version__!='2026.10.26':raise SpikeError('TOOL_VERSION')
    scope(url);plugin_dirs.value=[];all_plugins_loaded.value=True
    network=ApprovedNetwork()
    class ControlledYDL(yt_dlp.YoutubeDL):
        def urlopen(self,request):return network.open(request)
    with ControlledYDL(dict(quiet=True,no_warnings=True,logger=Quiet(),cachedir=False,skip_download=True,noplaylist=True,
            allowed_extractors=['generic','html5'],js_runtimes={},remote_components=[],retries=0,extractor_retries=0,
            postprocessors=[],force_generic_extractor=True,ignore_no_formats_error=True)) as ydl:
        info=ydl.sanitize_info(ydl.extract_info(url,download=False))
    if len(json.dumps(info,ensure_ascii=True,allow_nan=False).encode())>MAX_OUTPUT:raise SpikeError('OUTPUT_LIMIT')
    metadata=normalize(info,url)
    return dict(status='PASS',analysis=metadata,formats=len(metadata['formats']),download_enabled=False,eligible=False,reason='INTEGRATION_DISABLED')
