package com.bulktext.gateway.queue

import com.bulktext.gateway.cloud.CloudException
import com.bulktext.gateway.cloud.GatewayAttemptStart
import com.bulktext.gateway.cloud.GatewayDeliveryApi
import com.bulktext.gateway.identity.CredentialStore

class CloudDeliveryRepository(
    private val credentialStore: CredentialStore,
    private val api: GatewayDeliveryApi,
    private val localStore: SQLiteCloudQueueStore
) {
    fun beginAttempt(jobId: String, localAttempt: LocalCloudAttempt): GatewayAttemptStart {
        val deviceId = credentialStore.getDeviceId() ?: throw IllegalStateException("Pair this phone before SMS execution.")
        val credential = credentialStore.getCredential() ?: throw IllegalStateException("Gateway credential is unavailable.")
        val started = api.beginGatewayMessageAttempt(
            deviceId,
            credential,
            jobId,
            localAttempt.clientAttemptId,
            localAttempt.expectedParts
        )
        require(started.clientAttemptId == localAttempt.clientAttemptId) { "Server attempt identifier mismatch" }
        localStore.bindServerAttempt(localAttempt.clientAttemptId, started.attemptNumber)
        return started
    }

    fun flushEvents(limit: Int = 25): Int {
        val deviceId = credentialStore.getDeviceId() ?: return 0
        val credential = credentialStore.getCredential() ?: return 0
        var synced = 0
        for (pending in localStore.pendingAttemptEvents(limit.coerceIn(1, 25))) {
            try {
                api.reportGatewayMessageAttemptEvent(deviceId, credential, pending.event)
                localStore.markAttemptEventSynced(pending.event.eventId)
                synced += 1
            } catch (_: CloudException.NetworkException) {
                localStore.noteAttemptEventSyncFailure(pending.event.eventId)
                break
            } catch (e: Exception) {
                localStore.noteAttemptEventSyncFailure(pending.event.eventId)
                throw e
            }
        }
        return synced
    }

    fun syncRecoveryRequests(limit: Int = 25): Int {
        val deviceId = credentialStore.getDeviceId() ?: return 0
        val credential = credentialStore.getCredential() ?: return 0
        val requests = api.listGatewayMessageRecoveryRequests(deviceId, credential, limit.coerceIn(1, 25))
        var applied = 0
        for (request in requests) {
            val local = localStore.applyRecoveryRequest(deviceId, request)
            if (local == RecoveryApplyResult.NOT_APPLICABLE) continue
            // APPLIED / ALREADY_APPLIED and 0.18.1 REJECTED_UNSAFE are all
            // terminal locally and can be ACKed so obsolete requests do not loop.
            api.acknowledgeGatewayMessageRecovery(deviceId, credential, request.recoveryId)
            applied += 1
        }
        return applied
    }
}
