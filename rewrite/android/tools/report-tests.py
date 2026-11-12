"""Expose owned-fixture test failures as Actions annotations; preserve real counts."""
import pathlib
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1])
files = [p for p in root.rglob('*.xml') if p.name.startswith('TEST-')]
count = failed = skipped = 0
for path in files:
    data = ET.parse(path).getroot()
    count += int(data.get('tests', 0))
    skipped += int(data.get('skipped', 0))
    failed += int(data.get('failures', 0)) + int(data.get('errors', 0))
    for case in data.iter('testcase'):
        for error in list(case.findall('failure')) + list(case.findall('error')):
            message = (case.get('name', '') + ': ' + (error.text or error.get('message', '')))[:7000]
            message = message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
            print('::error title=Android fixture test::' + message)
print(f'::notice title=Android test report::{count} tests, {failed} failures/errors, {skipped} skipped')
if not count or failed or count == skipped:
    raise SystemExit(1)
