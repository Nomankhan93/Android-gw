#!/usr/bin/env python3
"""Package the verified source manifest only; preserve local configuration outside the archive."""
import argparse
import hashlib
import importlib.util
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('source_checksums', ROOT / 'scripts/source-checksums.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    expected = module.manifest_text()
    if (ROOT / 'SHA256SUMS.txt').read_text() != expected:
        raise SystemExit('Source checksum mismatch. Review changes before packaging.')
    version = re.search(r'versionName\s*=\s*"([0-9.a-z-]+)"', (ROOT / 'app/build.gradle.kts').read_text()).group(1)
    output = (args.output or ROOT.parent / f'bulktext-android-{version}-clean-source.zip').resolve()
    if output.exists(): raise SystemExit(f'Output already exists; choose a new --output path: {output}')
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for file in list(module.source_files()) + [ROOT / 'SHA256SUMS.txt']:
            archive.write(file, f'bulktext-android-{version}/' + file.relative_to(ROOT).as_posix())
    with zipfile.ZipFile(output) as archive:
        if archive.testzip(): raise SystemExit('ZIP integrity check failed')
    print(f'ZIP: {output}')
    print(f'SHA256: {hashlib.sha256(output.read_bytes()).hexdigest()}')

if __name__ == '__main__': main()
