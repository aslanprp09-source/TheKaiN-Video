"""Bounded owned UI preview through check annotations when artifact hosts are blocked."""
import base64
import hashlib
import json
from pathlib import Path
import sys

raw = (Path(sys.argv[1]) / 'installed-home.webp').read_bytes()
if len(raw) > 18000 or raw[:4] != b'RIFF' or raw[8:12] != b'WEBP':
    raise ValueError('Expected the bounded native screenshot')
encoded = base64.b64encode(raw).decode('ascii')
chunks = [encoded[i:i + 3500] for i in range(0, len(encoded), 3500)]
print('::notice title=M6 Windows preview metadata::' + json.dumps({
    'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw), 'chunks': len(chunks)}))
for index, chunk in enumerate(chunks, 1):
    print(f'::notice title=M6 Windows preview {index} of {len(chunks)}::{chunk}')
