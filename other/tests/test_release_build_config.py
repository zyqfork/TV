#!/usr/bin/env python3
"""Check daemon JDK portability and execute the CI release-version stamp in isolation."""
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def parse_code(tag):
    """Mirror Github.parseCode. Must stay aligned with the CI stamp formula."""
    text = tag.strip()
    if text[:1] in ('v', 'V'):
        text = text[1:]
    text = text.split('-', 1)[0].split('+', 1)[0]
    parts = text.split('.')
    major = int(parts[0]) if parts and parts[0] else 0
    minor = int(parts[1]) if len(parts) > 1 else 0
    patch = int(parts[2]) if len(parts) > 2 else 0
    return major * 10000 + minor * 100 + patch


def properties(text):
    return dict(line.split('=', 1) for line in text.splitlines()
                if line and not line.startswith('#') and '=' in line)


def main():
    daemon = properties((ROOT / 'gradle/gradle-daemon-jvm.properties').read_text(encoding='utf-8'))
    assert daemon == {'toolchainVersion': '21'}, daemon
    workflow = (ROOT / '.github/workflows/source-build.yml').read_text(encoding='utf-8')
    assert re.search(r'distribution: temurin\s+java-version: "21"', workflow)
    # Run the real workflow shell, not a reimplementation of its formula.
    block = workflow.split('      - name: Stamp version from release tag\n', 1)[1]
    block = block.split('      - uses:', 1)[0].split('        run: |\n', 1)[1]
    script = '\n'.join(line[10:] for line in block.splitlines() if line.startswith('          '))
    original = (ROOT / 'gradle.properties').read_text()
    current = properties(original)
    components = current['VERSION_NAME'].split('-', 1)[0].split('.')
    components += ['0'] * (3 - len(components))
    major, minor, patch = map(int, components)
    assert int(current['VERSION_CODE']) == major * 10000 + minor * 100 + patch
    cases = [('v5.5.10-source.5', 50510), ('v5.6', 50600), ('v5.6.0', 50600),
             ('v5.6.1', 50601), ('v5.7.0', 50700), ('v5.06.08', 50608), ('v6.0.0', 60000)]
    github = (ROOT / 'app/src/main/java/com/fongmi/android/tv/utils/Github.java').read_text(encoding='utf-8')
    assert 'return major * 10000 + minor * 100 + patch;' in github
    for tag, code in cases:
        assert parse_code(tag) == code, (tag, parse_code(tag), code)
    # Installed 5.6.0 must still see a later tag as newer.
    assert parse_code('v5.6.1') > 50600
    assert parse_code('v5.7.0') > 50600
    assert parse_code('v5.6.0') == 50600
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / 'gradle.properties'
        (Path(folder) / 'stamp.sh').write_text('GITHUB_REF_NAME="$1"\n' + script, encoding='utf-8', newline='\n')
        for tag, code in cases:
            path.write_text(original)
            subprocess.run(['bash', '-eu', 'stamp.sh', tag], cwd=folder, check=True,
                           capture_output=True, text=True)
            stamped = properties(path.read_text())
            assert stamped['VERSION_CODE'] == str(code), (tag, stamped)
            assert stamped['VERSION_NAME'] == tag[1:], (tag, stamped)
        path.write_text(original)
        subprocess.run(['bash', '-eu', 'stamp.sh', 'vnot-a-version'], cwd=folder,
                       check=True, capture_output=True, text=True)
        assert path.read_text() == original
    print('PASS installed JDK 21 accepted without vendor/download pin; real CI tag stamping, '
          'zero-padding, invalid tags and increasing release codes')


if __name__ == '__main__':
    main()
