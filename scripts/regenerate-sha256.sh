#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

find . -type f \
  ! -path './SHA256SUMS.txt' \
  ! -path './local.properties' \
  ! -path './.idea/*' \
  ! -path './.gradle/*' \
  ! -path './.kotlin/*' \
  ! -path './build/*' \
  ! -path './app/build/*' \
  ! -path './.patch-backups/*' \
  ! -path './.artifacts/*' \
  ! -name '*.apk' \
  ! -name '*.aab' \
  ! -name '*.jks' \
  ! -name '*.keystore' \
  -print0 \
  | sort -z \
  | while IFS= read -r -d '' f; do
      sha256sum "${f#./}"
    done > "$tmp"

mv "$tmp" SHA256SUMS.txt
trap - EXIT
sha256sum -c SHA256SUMS.txt >/dev/null
printf 'PASS: SHA256SUMS.txt regenerated and verified (%s files)\n' "$(wc -l < SHA256SUMS.txt)"
