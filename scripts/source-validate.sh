#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

bash scripts/test-core.sh
bash scripts/test-queue-core.sh
bash scripts/test-cloud-execution-core.sh
bash scripts/test-background-gateway-core.sh

grep -q 'versionName = "0.18.4"' app/build.gradle.kts
grep -q 'versionCode = 16' app/build.gradle.kts

for f in \
  app/src/main/java/com/bulktext/gateway/CloudSendingService.kt \
  app/src/main/java/com/bulktext/gateway/CloudSmsResultReceiver.kt \
  app/src/main/java/com/bulktext/gateway/BackgroundGatewayService.kt \
  app/src/main/java/com/bulktext/gateway/queue/CloudExecutionModels.kt \
  app/src/main/java/com/bulktext/gateway/queue/CloudAttemptModels.kt \
  app/src/main/java/com/bulktext/gateway/queue/CloudDeliveryRepository.kt \
  app/src/main/java/com/bulktext/gateway/cloud/GatewayDeliveryApi.kt \
  app/src/main/java/com/bulktext/gateway/cloud/GatewayDeliveryModels.kt; do
  test -f "$f"
done

grep -q 'CloudSmsResultReceiver' app/src/main/AndroidManifest.xml
grep -q 'CloudSendingService.*foregroundServiceType="remoteMessaging"' app/src/main/AndroidManifest.xml
grep -q 'BackgroundGatewayService.*foregroundServiceType="remoteMessaging"' app/src/main/AndroidManifest.xml
grep -q 'FOREGROUND_SERVICE_REMOTE_MESSAGING' app/src/main/AndroidManifest.xml

grep -q 'SQLiteOpenHelper(context, "cloud_queue.db", null, 6)' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt
grep -q 'CREATE TABLE IF NOT EXISTS cloud_attempts' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt
grep -q 'CREATE TABLE IF NOT EXISTS cloud_attempt_parts' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt
grep -q 'CREATE TABLE IF NOT EXISTS cloud_callback_outbox' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt
grep -q 'CREATE TABLE IF NOT EXISTS cloud_recovery_applied' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt
grep -q 'CREATE TABLE IF NOT EXISTS cloud_attempt_diagnostics' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt
grep -q 'ensurePreparedAttempt' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt
grep -q 'applyRecoveryRequest' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt
grep -q 'recoverCallbackTimeouts' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt

grep -q 'begin_gateway_message_attempt' app/src/main/java/com/bulktext/gateway/cloud/SupabaseGatewayApi.kt
grep -q 'report_gateway_message_attempt_event' app/src/main/java/com/bulktext/gateway/cloud/SupabaseGatewayApi.kt
grep -q 'list_gateway_message_recovery_requests' app/src/main/java/com/bulktext/gateway/cloud/SupabaseGatewayApi.kt
grep -q 'acknowledge_gateway_message_recovery' app/src/main/java/com/bulktext/gateway/cloud/SupabaseGatewayApi.kt

if command -v python3 >/dev/null 2>&1; then
  python3 scripts/validate-source-contracts.py
elif command -v python.exe >/dev/null 2>&1; then
  python.exe scripts/validate-source-contracts.py
else
  echo 'ERROR: Python is required for source validation.' >&2
  exit 127
fi

# Exactly one cloud SmsManager call site. Callback/background code must never send.
test "$(grep -c 'manager.sendMultipartTextMessage(current.phone' app/src/main/java/com/bulktext/gateway/CloudSendingService.kt)" -eq 1
! grep -qE 'sendMultipartTextMessage|sendTextMessage' app/src/main/java/com/bulktext/gateway/CloudSmsResultReceiver.kt
! grep -qE 'sendMultipartTextMessage|sendTextMessage' app/src/main/java/com/bulktext/gateway/BackgroundGatewayService.kt

grep -q 'CloudExecutionPolicy.exactSimMatches' app/src/main/java/com/bulktext/gateway/CloudSendingService.kt
grep -q 'CloudExecutionPolicy.segmentCountMatches' app/src/main/java/com/bulktext/gateway/CloudSendingService.kt
grep -q 'cloudDeliveryRepository.beginAttempt' app/src/main/java/com/bulktext/gateway/CloudSendingService.kt
grep -q 'beginSubmission(deviceId, current.jobId, localAttempt.clientAttemptId)' app/src/main/java/com/bulktext/gateway/CloudSendingService.kt
grep -q 'CloudSmsResultReceiver.KIND_SENT' app/src/main/java/com/bulktext/gateway/CloudSendingService.kt
grep -q 'CloudSmsResultReceiver.KIND_DELIVERY' app/src/main/java/com/bulktext/gateway/CloudSendingService.kt
grep -q 'markAttemptCallbackTimeout' app/src/main/java/com/bulktext/gateway/CloudSendingService.kt
grep -q 'OUTCOME_UNKNOWN' app/src/main/java/com/bulktext/gateway/CloudSendingService.kt

# Recovery-only polling while paused; execution remains blocked.
grep -q 'fun shouldServiceRun(enabled: Boolean): Boolean = enabled' app/src/main/java/com/bulktext/gateway/BackgroundGatewayPolicy.kt
grep -q 'fun canExecute(enabled: Boolean, pausedReason: String?)' app/src/main/java/com/bulktext/gateway/BackgroundGatewayPolicy.kt
grep -q 'syncRecoveryRequests' app/src/main/java/com/bulktext/gateway/BackgroundGatewayService.kt
grep -q 'Waiting for network to confirm recovery before any retry can execute.' app/src/main/java/com/bulktext/gateway/BackgroundGatewayService.kt
grep -q 'clearPause' app/src/main/java/com/bulktext/gateway/BackgroundGatewayPreferences.kt
grep -q 'retry_ready_at>=?' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt
grep -q 'Rejected unsafe callback-derived safe_retry request under 0.18.1' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt

# 0.18.1: callback failure after SmsManager is ambiguous; no callback-derived retry.
grep -q 'allSentCallbacksFailed' app/src/main/java/com/bulktext/gateway/queue/CloudExecutionModels.kt
grep -q 'callback failure after SmsManager is ambiguous' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt
grep -q 'Mixed multipart SENT results' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt
grep -q 'Operator continued without resending' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt

# Existing durable queue contract remains present.
grep -q 'claim_gateway_message_jobs' app/src/main/java/com/bulktext/gateway/cloud/SupabaseGatewayApi.kt
grep -q 'acknowledge_gateway_message_jobs' app/src/main/java/com/bulktext/gateway/cloud/SupabaseGatewayApi.kt
grep -q 'release_gateway_message_job_leases' app/src/main/java/com/bulktext/gateway/cloud/SupabaseGatewayApi.kt

# 0.18.2: UNKNOWN blocks SMS execution, never recovery/control-plane sync.
grep -q 'canEnableForRecovery' app/src/main/java/com/bulktext/gateway/BackgroundGatewayPolicy.kt
grep -q 'Background gateway active in recovery mode' app/src/main/java/com/bulktext/gateway/MainActivity.kt

# 0.18.3: persistent sync health and callback diagnostics.
grep -q 'markControlCycle' app/src/main/java/com/bulktext/gateway/BackgroundGatewayService.kt
grep -q 'markCallbackFlush' app/src/main/java/com/bulktext/gateway/BackgroundGatewayService.kt
grep -q 'markRecoverySync' app/src/main/java/com/bulktext/gateway/BackgroundGatewayService.kt
grep -q 'Gateway sync will retry automatically' app/src/main/java/com/bulktext/gateway/BackgroundGatewayService.kt
grep -q 'PLATFORM_ERROR_CODE_EXTRA = "errorCode"' app/src/main/java/com/bulktext/gateway/CloudSmsResultReceiver.kt
grep -q 'error_code INTEGER' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt
grep -q 'latestCallbackDiagnostics' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt
grep -q 'callbackDiagnostics' app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt
grep -q 'Latest callback diagnostics' app/src/main/java/com/bulktext/gateway/MainActivity.kt

# Certified local-lab transport remains byte-for-byte unchanged.
echo 'b738b06173ca61ccb657cea6009c2f37d3b3e8be63b216b5bad15abb819d51b0  app/src/main/java/com/bulktext/gateway/Policy.java' | sha256sum -c -
echo '6ce9cba6bd7f74aa730dba4af6b9c213e951b54187227159a79f4081c9fddb5f  app/src/main/java/com/bulktext/gateway/Telephony.kt' | sha256sum -c -
echo '62d8cacc3d52b4b057b58cd922dde2477d5dcab1c9504189f1dc37349f0c4e47  app/src/main/java/com/bulktext/gateway/SendingService.kt' | sha256sum -c -
echo 'f8d9a9335b23db7d86240363eb88cf5c9a3b890941f0f88eddd5f3420c108476  app/src/main/java/com/bulktext/gateway/SmsResultReceiver.kt' | sha256sum -c -
echo 'a37c855fcba7df3b172b82cf77700a6c9a27d132d26612bf5193e826b4b63a69  app/src/main/java/com/bulktext/gateway/Store.kt' | sha256sum -c -

sha256sum -c SHA256SUMS.txt >/dev/null
echo 'PASS: BulkText Android 0.18.4 source validation'
