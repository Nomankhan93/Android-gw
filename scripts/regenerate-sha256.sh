#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if command -v python3 >/dev/null 2>&1; then
  python3 scripts/source-checksums.py --write
  python3 scripts/source-checksums.py
else
  python.exe scripts/source-checksums.py --write
  python.exe scripts/source-checksums.py
fi
