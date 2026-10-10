package com.bulktext.gateway.queue

data class CloudQueueCounts(
    val persistedJobs: Int,
    val unacknowledgedJobs: Int,
    val acknowledgedJobs: Int,
    val latestSyncAt: Long?
)

sealed class CloudQueueState {
    object NotSynced : CloudQueueState()
    object Syncing : CloudQueueState()
    data class Ready(
        val claimedJobs: Int,
        val acknowledgedThisSync: Int,
        val counts: CloudQueueCounts
    ) : CloudQueueState()
    data class Offline(val counts: CloudQueueCounts) : CloudQueueState()
    data class CredentialRejected(val counts: CloudQueueCounts) : CloudQueueState()
    data class SimBlocked(val message: String, val counts: CloudQueueCounts) : CloudQueueState()
    data class Error(val message: String, val counts: CloudQueueCounts) : CloudQueueState()
}

data class CloudQueueExecutionIdentity(
    val deviceId: String,
    val gatewaySimId: String,
    val subscriptionId: Int,
    val slotIndex: Int,
    val simIdentityHash: String
)

class CloudQueueIntegrityException(message: String) : IllegalStateException(message)
