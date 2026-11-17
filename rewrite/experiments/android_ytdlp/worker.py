"""Actual pinned yt-dlp, restricted to an owned HTTPS fixture origin. No media transfer."""
import io
import json
from pathlib import Path
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parent))
from spike import MAX_OUTPUT, VERSION, SpikeError, origin


def install_guard(fixture_origin):
    def audit(event, args):
        if event in ('subprocess.Popen', 'os.system', 'os.posix_spawn', 'os.exec', 'os.fork'):
            raise SpikeError('UNSUPPORTED_MEDIA')
        if event == 'socket.connect' and args[1] != fixture_origin[1:]:
            raise SpikeError('OUT_OF_SCOPE_URL')
        if event == 'socket.getaddrinfo' and args[:2] != fixture_origin[1:]:
            raise SpikeError('OUT_OF_SCOPE_URL')
    sys.addaudithook(audit)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class FixtureNetwork:
    """One numeric loopback origin, checked before every redirect/request; no proxy."""
    def __init__(self, url, ca):
        self.origin = origin(url)
        self.deadline = time.monotonic() + 8
        self.requests = 0
        self.bytes = 0
        context = ssl.create_default_context(cafile=ca)
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            NoRedirect, urllib.request.HTTPSHandler(context=context))

    def open(self, req):
        from yt_dlp.networking.common import Response
        url = req if isinstance(req, str) else req.url
        method = 'GET' if isinstance(req, str) else req.method
        if method not in ('GET', 'HEAD'):
            raise SpikeError('UNSUPPORTED_MEDIA')
        for _ in range(6):
            if origin(url) != self.origin:
                raise SpikeError('OUT_OF_SCOPE_URL')
            self.requests += 1
            if self.requests > 12:
                raise SpikeError('REQUEST_LIMIT')
            remaining = self.deadline - time.monotonic()
            if remaining <= 0:
                raise SpikeError('TIMEOUT')
            # Ignore all upstream request cookies/credentials/custom headers.
            request = urllib.request.Request(url, method=method, headers={
                'User-Agent': 'TheKaiN-owned-fixture-spike', 'Accept-Encoding': 'identity'})
            try:
                with self.opener.open(request, timeout=min(remaining, 3)) as response:
                    encoding = response.headers.get('Content-Encoding', 'identity')
                    if encoding != 'identity':
                        raise SpikeError('UNSUPPORTED_MEDIA')
                    if method == 'GET' and response.headers.get_content_type() != 'text/html':
                        raise SpikeError('UNSUPPORTED_MEDIA')
                    if int(response.headers.get('Content-Length', '0')) > 512 * 1024 and method != 'HEAD':
                        raise SpikeError('RESPONSE_LIMIT')
                    raw = b'' if method == 'HEAD' else response.read(512 * 1024 + 1)
                    self.bytes += len(raw)
                    if len(raw) > 512 * 1024 or self.bytes > 2 * 1024 * 1024:
                        raise SpikeError('RESPONSE_LIMIT')
                    return Response(io.BytesIO(raw), url, dict(response.headers), response.status)
            except urllib.error.HTTPError as error:
                if error.code in (301, 302, 303, 307, 308):
                    url = urllib.parse.urljoin(url, error.headers.get('Location', ''))
                    error.close()
                    continue
                code = {403: 'HTTP_FORBIDDEN', 404: 'REMOTE_MISSING', 429: 'RATE_LIMITED'}.get(error.code, 'NETWORK_ERROR')
                error.close()
                raise SpikeError(code) from None
            except (TimeoutError, ssl.SSLError) as error:
                raise SpikeError('TLS_ERROR' if isinstance(error, ssl.SSLError) else 'TIMEOUT') from None
            except urllib.error.URLError as error:
                raise SpikeError('TLS_ERROR' if isinstance(error.reason, ssl.SSLError) else 'NETWORK_ERROR') from None
        raise SpikeError('REQUEST_LIMIT')


class Quiet:
    def debug(self, *_): pass
    def warning(self, *_): pass
    def error(self, *_): pass


def main():
    try:
        install_guard(origin(sys.argv[1]))
        import yt_dlp
        from yt_dlp.version import __version__
        from yt_dlp.globals import plugin_dirs, all_plugins_loaded
        if __version__ != VERSION:
            raise SpikeError('TOOL_VERSION')
        plugin_dirs.value = []
        all_plugins_loaded.value = True
        network = FixtureNetwork(sys.argv[1], sys.argv[2])
        class OwnedFixtureYDL(yt_dlp.YoutubeDL):
            def urlopen(self, req): return network.open(req)
        with OwnedFixtureYDL({'quiet': True, 'no_warnings': True, 'logger': Quiet(),
                'cachedir': False, 'skip_download': True, 'noplaylist': True,
                'format': 'best[acodec!=none][vcodec!=none]',
                'extract_flat': False, 'allowed_extractors': ['generic', 'html5'],
                'js_runtimes': {}, 'remote_components': [], 'socket_timeout': 3,
                'retries': 0, 'extractor_retries': 0, 'postprocessors': [],
                'force_generic_extractor': True, 'ignore_no_formats_error': True}) as ydl:
            info = ydl.extract_info(sys.argv[1], download=False)
            raw = json.dumps(ydl.sanitize_info(info), ensure_ascii=True, allow_nan=False).encode()
            if len(raw) > MAX_OUTPUT:
                raise SpikeError('OUTPUT_LIMIT')
            sys.stdout.buffer.write(raw)
        return 0
    except Exception as error:
        code = error.code if isinstance(error, SpikeError) else (
            'TOOL_MISSING' if isinstance(error, ModuleNotFoundError) else 'TOOL_FAILED')
        print(json.dumps({'error_code': code}))
        return 2


if __name__ == '__main__':
    sys.exit(main())
