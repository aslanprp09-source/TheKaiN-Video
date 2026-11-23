"""The additional gate must execute all three real HTTPS tests, with no skips."""
import pathlib
import re
import sys


def verify(text):
    return (re.search(r'^OK \(3 tests\)\s*$', text, re.MULTILINE) is not None
            and len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0\s*$', text, re.MULTILINE)) == 3
            and not re.search(r'FAILURES!!!|shortMsg=|INSTRUMENTATION_(?:FAILED|ABORTED)|'
                              r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b', text))


if __name__ == '__main__':
    content = pathlib.Path(sys.argv[1]).read_text()
    print(content)
    if not verify(content):
        print('::error title=Direct HTTPS reliability::Expected all three tests without failures or skips')
        raise SystemExit(1)
    print('::notice title=Direct HTTPS reliability::3 executed tests; no failures or skips')
