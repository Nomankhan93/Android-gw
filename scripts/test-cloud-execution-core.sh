#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# This standalone pure-Kotlin test is a fast extra check when a standalone
# Kotlin compiler is installed. Android Studio/AGP can compile Kotlin without
# publishing `kotlinc` as a shell command, so lack of `kotlinc` must not block
# the release validator. The mandatory Gradle JUnit stage immediately after
# source validation compiles and runs GatewayCloudExecutionTest, which covers
# the same CloudExecutionPolicy invariants.
if ! command -v kotlinc >/dev/null 2>&1; then
  test -f app/src/test/java/com/bulktext/gateway/GatewayCloudExecutionTest.kt
  grep -q 'onlyAckedReadyJobCanBegin' app/src/test/java/com/bulktext/gateway/GatewayCloudExecutionTest.kt
  grep -q 'interruptedSubmittingRecoversUnknown' app/src/test/java/com/bulktext/gateway/GatewayCloudExecutionTest.kt
  grep -q 'exactSimRequiresAllFrozenIdentityFields' app/src/test/java/com/bulktext/gateway/GatewayCloudExecutionTest.kt
  grep -q 'segmentCountMustRemainExact' app/src/test/java/com/bulktext/gateway/GatewayCloudExecutionTest.kt
  grep -q 'campaignBatchIsBoundedAndStopsOnUnknown' app/src/test/java/com/bulktext/gateway/GatewayCloudExecutionTest.kt
  echo 'DEFERRED: standalone kotlinc is not on PATH; CloudExecutionPolicy execution tests will run in mandatory Gradle JUnit GatewayCloudExecutionTest.'
  exit 0
fi

if ! command -v java >/dev/null 2>&1; then
  echo 'ERROR: kotlinc is available but java is not on PATH.' >&2
  exit 127
fi

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
kotlinc \
  app/src/main/java/com/bulktext/gateway/queue/CloudExecutionModels.kt \
  tests/CloudExecutionCoreTest.kt \
  -include-runtime -d "$out/cloud-execution-core.jar"
java -jar "$out/cloud-execution-core.jar"
