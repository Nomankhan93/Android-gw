#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

KOTLINC=""
if command -v kotlinc &> /dev/null; then
  KOTLINC="kotlinc"
elif [ -f "/c/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/bin/kotlinc" ]; then
  KOTLINC="/c/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/bin/kotlinc"
elif [ -f "C:/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/bin/kotlinc.bat" ]; then
  KOTLINC="C:/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/bin/kotlinc.bat"
elif [ -f "/mnt/c/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/bin/kotlinc" ]; then
  KOTLINC="/mnt/c/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/bin/kotlinc"
else
  echo "Error: kotlinc not found in PATH or Android Studio plugin path." >&2
  exit 1
fi

"$KOTLINC" \
  app/src/main/java/com/bulktext/gateway/cloud/CloudException.kt \
  app/src/main/java/com/bulktext/gateway/cloud/GatewayQueueApi.kt \
  app/src/main/java/com/bulktext/gateway/cloud/GatewayQueueModels.kt \
  app/src/main/java/com/bulktext/gateway/identity/CredentialStore.kt \
  app/src/main/java/com/bulktext/gateway/queue/CloudQueueState.kt \
  app/src/main/java/com/bulktext/gateway/queue/CloudQueueLocalStore.kt \
  app/src/main/java/com/bulktext/gateway/queue/CloudJobReconciler.kt \
  app/src/main/java/com/bulktext/gateway/queue/CloudQueueRepository.kt \
  tests/QueueCoreTest.kt \
  -include-runtime -d "$out/queue-core.jar"

java -jar "$out/queue-core.jar"
