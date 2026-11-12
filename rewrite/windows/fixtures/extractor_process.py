"""Offline subprocess double; no media-provider requests or downloaded code."""
import sys
import time
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')  # Match the actual extractor machine protocol.

mode = sys.argv[1]
if mode == "wait":
    time.sleep(20)
elif mode == "error":
    print("ERROR: HTTP Error 403: Forbidden https://example.invalid/?token=secret-fixture", file=sys.stderr)
    sys.exit(1)
elif mode == "bad-json":
    print("not JSON")
else:
    print(Path(__file__).with_name(mode + ".json").read_text(encoding="utf-8"))
