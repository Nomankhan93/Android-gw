#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

bash scripts/test-core.sh
./gradlew --no-daemon clean test :app:assembleDebug :app:lintDebug

python3 - <<'PY'
import json
from pathlib import Path
meta = json.loads(Path('app/build/outputs/apk/debug/output-metadata.json').read_text())
e = meta['elements'][0]
assert e['versionName'] == '0.4.1', e
assert int(e['versionCode']) == 5, e
print(f"PASS: APK {e['versionName']} ({e['versionCode']})")
PY
printf '\nAPK: %s/app/build/outputs/apk/debug/app-debug.apk\n' "$PWD"
