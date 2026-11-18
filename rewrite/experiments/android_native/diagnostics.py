"""Bounded tails of own source-build logs; no environment dump or archive URLs."""
import argparse
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--work', type=Path, required=True)
args = parser.parse_args()
for path in sorted(args.work.glob('*.log')):
    print(path.name)
    print(path.read_text(errors='replace')[-6000:])
