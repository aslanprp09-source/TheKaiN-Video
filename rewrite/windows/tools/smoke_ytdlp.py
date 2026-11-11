"""Optional real yt-dlp JSON round trip using owned synthetic metadata, offline."""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from extractor import parse_metadata


def main():
    data = json.loads((ROOT / 'fixtures/one.json').read_text(encoding='utf-8'))
    data.update(extractor='fixture', webpage_url='https://example.invalid/authorized-fixture')
    for f in data['formats']:
        f['url'] = 'https://example.invalid/fixture.mp4'
    with tempfile.TemporaryDirectory() as directory:
        info = Path(directory) / 'fixture.json'
        info.write_text(json.dumps(data), encoding='utf-8')
        result = subprocess.run(
            [sys.executable, '-m', 'yt_dlp', '--ignore-config', '--skip-download',
             '--no-check-formats', '--dump-single-json', '--load-info-json', str(info)],
            capture_output=True, text=True, encoding='utf-8', timeout=10, check=True)
        parsed = parse_metadata(json.loads(result.stdout), data['webpage_url'])
        assert parsed.media and len(parsed.media.formats) == 1
        assert parsed.media.formats[0].extractor_format_id == 'mixed-360'
    print('Real yt-dlp offline info-json round trip passed (no media requests)')


if __name__ == '__main__':
    main()
