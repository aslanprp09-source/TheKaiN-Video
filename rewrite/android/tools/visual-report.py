"""Validate full PNG evidence; expose a small review bundle through the allowed GitHub API.

Full-resolution originals remain in the Actions artifact. No app/user data is used:
these are the explicitly named renderer fixtures produced by M5VisualTest.
"""
import base64
import io
import pathlib
import sys
import zipfile
from PIL import Image, ImageDraw

folder = pathlib.Path(sys.argv[1])
cases = ('home-empty', 'home-ready', 'downloads-running', 'downloads-failed', 'downloads-completed', 'library', 'settings')
configs = [(width, theme) for width in (360, 412, 600) for theme in ('dark', 'light')]
sheet = Image.new('RGB', (7 * 190, 6 * 630), '#dce0e6')
draw = ImageDraw.Draw(sheet)
for row, (width, theme) in enumerate(configs):
    for col, case in enumerate(cases):
        path = folder / f'{width}-{theme}-{case}.png'
        with Image.open(path) as image:
            assert image.format == 'PNG' and image.width == width and image.height > 300, path.name
            thumb = image.convert('RGB')
            thumb.thumbnail((182, 596))
            sheet.paste(thumb, (col * 190 + 4, row * 630 + 28))
        draw.text((col * 190 + 4, row * 630 + 4), f'{width} {theme} {case}', fill='#17202e')
sheet.save(folder / 'contact-sheet.webp', quality=85)
bundle = io.BytesIO()
with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED) as archive:
    archive.write(folder / 'contact-sheet.webp', 'contact-sheet.webp')
    for name in ('360-dark-home-ready', '360-light-settings', '600-dark-home-ready'):
        image = Image.open(folder / f'{name}.png').convert('RGB')
        output = io.BytesIO()
        image.save(output, 'WEBP', quality=92)
        archive.writestr(f'{name}.webp', output.getvalue())
(folder / 'review-bundle.zip').write_bytes(bundle.getvalue())
encoded = base64.b64encode(bundle.getvalue()).decode('ascii')
chunks = [encoded[i:i+60000] for i in range(0, len(encoded), 60000)]
assert len(chunks) <= 9, 'Review bundle exceeds the bounded annotation budget'
for i, chunk in enumerate(chunks, 1):
    print(f'::notice title=M5 review bundle {i}/{len(chunks)}::{chunk}')
print('::notice title=M5 evidence::42 PNGs validated; renderer fixtures, not website/device release claims')
