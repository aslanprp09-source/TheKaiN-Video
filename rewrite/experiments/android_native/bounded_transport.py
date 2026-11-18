"""Only the fixed native-to-Java HTTPS gate; Python owns no network sockets."""
import io
import _thekain_transport
from spike import SpikeError


class FixtureNetwork:
    def open(self, request):
        from yt_dlp.networking.common import Response
        url=request if isinstance(request,str) else request.url
        method='GET' if isinstance(request,str) else request.method
        try:
            body=_thekain_transport.fetch(url,method)
        except _thekain_transport.Error as error:
            raise SpikeError(str(error)) from None
        return Response(io.BytesIO(body),url,{'Content-Type':'text/html'},200)
