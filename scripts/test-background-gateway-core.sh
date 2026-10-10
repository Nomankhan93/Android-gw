#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if ! command -v kotlinc >/dev/null 2>&1; then
  echo "DEFERRED: standalone kotlinc is not on PATH; BackgroundGatewayPolicy tests will run in mandatory Gradle JUnit/source checks."
  exit 0
fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc \
  app/src/main/java/com/bulktext/gateway/BackgroundGatewayPolicy.kt \
  tests/BackgroundGatewayCoreTest.kt \
  -include-runtime -d "$TMP/background-gateway-core.jar"
java -jar "$TMP/background-gateway-core.jar"
