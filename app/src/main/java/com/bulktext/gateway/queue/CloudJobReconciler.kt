package com.bulktext.gateway.queue

import com.bulktext.gateway.cloud.GatewayQueueJob

/**
 * Pure reconciliation rules for one stable server job id.
 * The immutable payload may never change. Only a strictly newer lease may
 * replace locally stored lease metadata after server-side lease expiry/reclaim.
 */
object CloudJobReconciler {
    data class StoredShape(
        val job: GatewayQueueJob,
        val acknowledged: Boolean
    )

    fun decide(existing: StoredShape?, incoming: GatewayQueueJob): PersistResult {
        if (existing == null) return PersistResult.INSERTED
        ensureImmutablePayloadMatches(existing.job, incoming)

        if (existing.acknowledged) {
            throw CloudQueueIntegrityException("Server re-returned a cloud job that this device already ACKed")
        }

        if (incoming.leaseVersion < existing.job.leaseVersion) {
            throw CloudQueueIntegrityException("Server returned an older lease version for an existing cloud job")
        }
        if (incoming.leaseVersion == existing.job.leaseVersion) {
            if (incoming.leaseToken != existing.job.leaseToken) {
                throw CloudQueueIntegrityException("Same cloud job lease version returned with a different lease token")
            }
            return PersistResult.SAME_LEASE
        }

        // A newer lease is the normal recovery path when persistence succeeded,
        // ACK did not, and the prior server lease expired/requeued.
        return PersistResult.NEWER_LEASE
    }

    private fun ensureImmutablePayloadMatches(a: GatewayQueueJob, b: GatewayQueueJob) {
        val same =
            a.jobId == b.jobId &&
            a.dispatchId == b.dispatchId &&
            a.campaignId == b.campaignId &&
            a.sourceRowNumber == b.sourceRowNumber &&
            a.normalizedE164 == b.normalizedE164 &&
            a.renderedMessage == b.renderedMessage &&
            a.smsEncoding == b.smsEncoding &&
            a.segmentCount == b.segmentCount &&
            a.gatewaySimId == b.gatewaySimId &&
            a.simSubscriptionId == b.simSubscriptionId &&
            a.simSlotIndex == b.simSlotIndex &&
            a.simIdentityHash == b.simIdentityHash
        if (!same) {
            throw CloudQueueIntegrityException("Immutable cloud job payload changed for stable job id ${a.jobId}")
        }
    }
}
