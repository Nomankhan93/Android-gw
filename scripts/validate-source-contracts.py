from pathlib import Path
import os
os.chdir(Path(__file__).resolve().parents[1])

# 1. Reconciler check
r = Path('app/src/main/java/com/bulktext/gateway/queue/CloudJobReconciler.kt').read_text()
assert r.index('if (existing.acknowledged)') < r.index('if (incoming.leaseVersion <')

# 2. Supabase API tokens
a = Path('app/src/main/java/com/bulktext/gateway/cloud/SupabaseGatewayApi.kt').read_text()
for token in ['statusCode == 401', 'statusCode == 403', 'code == "42501"']:
    assert token in a, token

# 3. CloudSendingService order check
s = Path('app/src/main/java/com/bulktext/gateway/CloudSendingService.kt').read_text()
prepare = s.index('ensurePreparedAttempt')
server = s.index('cloudDeliveryRepository.beginAttempt')
submitting = s.index('beginSubmission(deviceId, current.jobId, localAttempt.clientAttemptId)')
sms = s.index('manager.sendMultipartTextMessage(current.phone')
submitted = s.index('markSubmitted(deviceId, current.jobId)')
assert prepare < server < submitting < sms < submitted, (prepare, server, submitting, sms, submitted)

# 4. Receiver order check
receiver = Path('app/src/main/java/com/bulktext/gateway/CloudSmsResultReceiver.kt').read_text()
assert receiver.index('val callbackResult = resultCode') < receiver.index('val pending = goAsync()')
assert 'cloudDeliveryRepository.flushEvents' not in receiver
assert receiver.index('recordAttemptCallback') < receiver.index('CallbackSyncService.schedule')
assert 'deliveryEvidence = evidence' in receiver
assert 'CloudCallbackPolicy.pendingIntentFlags()' in s
assert 'submissionGate.runIfAllowed(permit)' in s
assert s.index('submissionGate.runIfAllowed(permit)') < submitting < sms
assert 'EXTRA_STOP_GENERATION' in s

# 5. Background service checks
bg = Path('app/src/main/java/com/bulktext/gateway/BackgroundGatewayService.kt').read_text()
assert bg.index('syncRecoveryRequests') < bg.index('nextReadySince(deviceId, activatedAt)')
assert 'shouldServiceRun' in bg and 'canExecute' in bg
assert bg.count('Waiting for network to confirm recovery before any retry can execute.') >= 2
assert 'cloudQueueRepository.sync' in bg
assert 'if (stopping || !prefs.submissionGate.isAllowed(permit)) return' in bg
assert '.putExtra(CloudSendingService.EXTRA_STOP_GENERATION, permit.generation)' in bg
final_unknown = bg.index('if (finalExecution.unknown > 0)')
assert bg.index('cloudQueueRepository.sync') < final_unknown
assert bg.count('syncRecoveryRequests()') >= 2
assert 'Queue/callback/recovery sync is active; sending remains paused.' in bg

# 6. Store check
store = Path('app/src/main/java/com/bulktext/gateway/queue/SQLiteCloudQueueStore.kt').read_text()
assert 'putNull("current_attempt_id")' not in store[store.index('"safe_retry" ->'):store.index('"skip_unknown" ->')]

worker = Path('app/src/main/java/com/bulktext/gateway/CallbackSyncService.kt').read_text()
assert 'flushEvents(10)' in worker
for forbidden in ['sendMultipartTextMessage', 'sendTextMessage', 'syncRecoveryRequests', 'claimGatewayMessageJobs']:
    assert forbidden not in worker
assert 'recovery_ack_outbox' in store
assert 'conflict_latched' in store
assert 'synced_at IS NULL AND disposition IS NULL AND device_id=?' in store
for rpc in ['begin_gateway_message_attempt_v2', 'report_gateway_message_attempt_event_v2', 'acknowledge_gateway_message_recovery_v2']:
    assert rpc in a
assert 'UUID.randomUUID().toString()' in receiver
assert 'Callback UUID payload changed' in store
assert 'unknown_resolved=0' in store
print("PASS: 0.18.5-r1 source boundary contracts (static checks, not Android runtime tests)")
