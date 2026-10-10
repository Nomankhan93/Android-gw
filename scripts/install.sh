#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v adb >/dev/null || { echo 'Install Android platform-tools and put adb on PATH.'; exit 1; }
apk="app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$apk" ]] || { echo 'Build the fresh 0.4.1 APK first with bash scripts/build.sh'; exit 1; }
adb install -r "$apk"
adb shell am start -n com.bulktext.gateway.prototype/com.bulktext.gateway.MainActivity
