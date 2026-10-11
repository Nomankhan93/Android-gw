package com.bulktext.gateway.queue

import com.bulktext.gateway.cloud.CloudException
import com.bulktext.gateway.cloud.GatewayAttemptStart
import com.bulktext.gateway.cloud.GatewayDeliveryApi
import com.bulktext.gateway.identity.CredentialStore

class CloudDeliveryRepository(
    private val credentialStore: CredentialStore,
    private val api: GatewayDeliveryApi,
    private val localStore: SQLiteCloudQueueStore,
    private val health: com.bulktext.gateway.BackgroundGatewayPreferences? = null
) {
    fun beginAttempt(jobId: String, localAttempt: LocalCloudAttempt): GatewayAttemptStart = tracked("begin") { beginAttemptInternal(jobId, localAttempt) }
    fun flushEvents(limit: Int = 25): Int = tracked("callbacks") { flushEventsInternal(limit) }
    fun syncRecoveryRequests(limit: Int = 25): Int = tracked("recovery") { syncRecoveryRequestsInternal(limit) }

    private fun <T> tracked(operation: String, action: () -> T): T {
        val device = credentialStore.getDeviceId()
        try {
            val result = action()
            if (device != null) health?.recordOperation(device, operation)
            return result
        } catch (e: Exception) {
            if (device != null) health?.recordOperation(device, operation, e)
            throw e
        }
    }

    private fun beginAttemptInternal(jobId: String, localAttempt: LocalCloudAttempt): GatewayAttemptStart {
        val deviceId = credentialStore.getDeviceId() ?: throw IllegalStateException("Pair this phone before SMS execution.")
        val credential = credentialStore.getCredential() ?: throw IllegalStateException("Gateway credential is unavailable.")
        require(localStore.getExecutable(deviceId, jobId) != null) { "Attempt owner mismatch" }
        val started = api.beginGatewayMessageAttempt(
            deviceId,
            credential,
            jobId,
            localAttempt.clientAttemptId,
            localAttempt.expectedParts
        )
        require(localStore.getExecutable(deviceId, jobId) != null) { "Attempt belongs to another gateway identity" }
        if (started.outcome == "blocked" || !started.canSubmit) {
            localStore.blockBeforeSubmission(deviceId, jobId, started.blockCode ?: "attempt_not_prepared")
            return started
        }
        require(started.clientAttemptId == localAttempt.clientAttemptId) { "Server attempt identifier mismatch" }
        localStore.bindServerAttempt(localAttempt.clientAttemptId, started.attemptNumber)
        return started
    }

    private fun flushEventsInternal(limit: Int = 25): Int {
        val deviceId = credentialStore.getDeviceId() ?: return 0
        val credential = credentialStore.getCredential() ?: return 0
        var synced = 0
        for (pending in localStore.pendingAttemptEvents(deviceId, limit.coerceIn(1, 25))) {
            try {
                val report = api.reportGatewayMessageAttemptEvent(deviceId, credential, pending.event)
                localStore.completeReport(pending.event.eventId, report)
                synced += 1
            } catch (e: Exception) {
                localStore.noteAttemptEventSyncFailure(pending.event.eventId)
                throw e
            }
        }
        return synced
    }

    private fun syncRecoveryRequestsInternal(limit: Int = 25): Int {
        val deviceId = credentialStore.getDeviceId() ?: return 0
        val credential = credentialStore.getCredential() ?: return 0
        var acknowledged = flushRecoveryAcks(deviceId, credential)
        val requests = api.listGatewayMessageRecoveryRequests(deviceId, credential, limit.coerceIn(1, 25))
        for (request in requests) localStore.applyRecoveryRequest(deviceId, request)
        acknowledged += flushRecoveryAcks(deviceId, credential)
        return acknowledged
    }

    private fun flushRecoveryAcks(deviceId: String, credential: String): Int {
        var count = 0
        for ((id, outcome) in localStore.pendingRecoveryAcks(deviceId)) {
            if (api.acknowledgeGatewayMessageRecovery(deviceId, credential, id, outcome)) {
                localStore.markRecoveryAcked(deviceId, id); count++
            }
        }
        return count
    }
}
