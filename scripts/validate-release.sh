#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

bash scripts/source-validate.sh
./gradlew clean testDebugUnitTest :app:assembleDebug :app:lintDebug

python3 scripts/verify-release-metadata.py
