#!/usr/bin/env python3
"""Portable source-only manifest. No Git state, builds, backups, credentials or old archives."""
from pathlib import Path
import argparse
import hashlib

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {'.git', '.gradle', '.kotlin', '.idea', '.artifacts', '.patch-backups', 'build', 'captures', '__pycache__', 'node_modules'}
ROOT_FILES = {'.gitignore', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat'}
SOURCE_DIRS = {'app', 'gradle', 'scripts', 'tests', 'docs'}
FORBIDDEN_NAMES = {'local.properties', 'key.properties', 'keystore.properties'}
FORBIDDEN_SUFFIXES = {'.zip', '.apk', '.aab', '.apks', '.log', '.pyc', '.jks', '.keystore', '.p12', '.pfx', '.hprof', '.iml'}

def source_files():
    for path in sorted(ROOT.rglob('*'), key=lambda p: p.relative_to(ROOT).as_posix()):
        rel = path.relative_to(ROOT)
        if any(part in EXCLUDED for part in rel.parts): continue
        if path.name in FORBIDDEN_NAMES or path.name.startswith('.env') or path.suffix.lower() in FORBIDDEN_SUFFIXES: continue
        if rel.parts[0] not in SOURCE_DIRS and rel.as_posix() not in ROOT_FILES and not (len(rel.parts) == 1 and path.suffix == '.md'): continue
        if path.is_symlink(): raise SystemExit(f'Symlink is not a source release file: {rel}')
        if path.is_file(): yield path

def manifest_text():
    return ''.join(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(ROOT).as_posix()}\n' for path in source_files())

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='Regenerate only after reviewed source changes')
    args = parser.parse_args()
    target = ROOT / 'SHA256SUMS.txt'
    expected = manifest_text()
    if args.write:
        target.write_text(expected, encoding='utf-8', newline='\n')
        print(f'Wrote source-only checksums ({len(expected.splitlines())} files).')
    elif target.read_text(encoding='utf-8') != expected:
        raise SystemExit('FAIL: source checksums differ. Review changes before running --write; never regenerate merely to hide a mismatch.')
    else:
        print(f'PASS: source-only checksums ({len(expected.splitlines())} files).')

if __name__ == '__main__': main()
