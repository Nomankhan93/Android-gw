#!/usr/bin/env python3
"""Verify APK metadata against the version declared in app/build.gradle.kts."""
import hashlib
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
config = (root / 'app/build.gradle.kts').read_text()
name = re.search(r'versionName\s*=\s*"([^"]+)"', config).group(1)
code = int(re.search(r'versionCode\s*=\s*(\d+)', config).group(1))
metadata = root / 'app/build/outputs/apk/debug/output-metadata.json'
apk = metadata.parent / 'app-debug.apk'
elements = json.loads(metadata.read_text())['elements']
assert len(elements) == 1, 'Expected one debug APK output'
element = elements[0]
assert element['versionName'] == name, element
assert int(element['versionCode']) == code, element
assert apk.is_file(), apk
print(f'PASS: Android Gateway {name} / versionCode {code} APK metadata')
print(f'APK: {apk}')
print(f'SHA256: {hashlib.sha256(apk.read_bytes()).hexdigest()}')
