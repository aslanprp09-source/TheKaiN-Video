"""Run with a real display (or Xvfb); optional ImageMagick screenshots."""
import argparse
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import App
from extractor import SourceAnalyzer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--screenshots', type=Path)
    args = parser.parse_args()
    app = App()
    fixture = Path(__file__).resolve().parents[1] / 'fixtures/extractor_process.py'

    def capture(name):
        app.update()
        if args.screenshots:
            args.screenshots.mkdir(parents=True, exist_ok=True)
            subprocess.run(['import', '-window', str(app.winfo_id()),
                            str(args.screenshots / (name + '.png'))], check=True)

    def analyze(mode):
        app.analyzer = SourceAnalyzer([sys.executable, str(fixture), mode])
        app.analyze()
        deadline = time.monotonic() + 5
        while app.analyzing and time.monotonic() < deadline:
            app.update()
            time.sleep(.02)
        assert not app.analyzing, 'Analysis did not finish'

    try:
        capture('home-empty')
        app.url.set('https://example.invalid/authorized-fixture')
        analyze('three')
        assert app.media and len(app.media.formats) == 3
        assert str(app.start_btn['state']) == 'normal'
        capture('home-ready')
        app.url.set('https://example.invalid/edited')
        assert app.media is None and str(app.start_btn['state']) == 'disabled'
        analyze('error')
        assert '403' in app.status.get() and 'token' not in app.status.get()
        assert str(app.start_btn['state']) == 'disabled'
        capture('home-error')
        print('Tk smoke passed: empty, 3 variants, URL invalidation, localized 403')
    finally:
        app.close()


if __name__ == '__main__':
    main()
