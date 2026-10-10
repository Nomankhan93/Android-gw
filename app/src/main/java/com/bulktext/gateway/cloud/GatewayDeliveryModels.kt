package com.bulktext.gateway.cloud

data class GatewayAttemptStart(
    val attemptId: String,
    val clientAttemptId: String,
    val attemptNumber: Int,
    val state: String,
    val safeRetryEligible: Boolean,
    val createdAt: Long
)

data class GatewayAttemptEvent(
    val eventId: String,
    val clientAttemptId: String,
    val eventType: String,
    val partIndex: Int?,
    val resultCode: Int?,
    val occurredAtMillis: Long
) {
    init {
        require(eventId.isNotBlank())
        require(clientAttemptId.isNotBlank())
        require(eventType in setOf("submitted", "sent", "delivery", "unknown"))
        if (eventType == "sent" || eventType == "delivery") {
            require(partIndex != null && partIndex >= 0)
            require(resultCode != null)
        } else {
            require(partIndex == null)
        }
        require(occurredAtMillis > 0L)
    }
}

data class GatewayAttemptReport(
    val attemptId: String,
    val clientAttemptId: String,
    val attemptNumber: Int,
    val state: String,
    val safeRetryEligible: Boolean,
    val sentParts: Int,
    val failedSentParts: Int,
    val deliveredParts: Int
)

data class GatewayRecoveryRequest(
    val recoveryId: String,
    val action: String,
    val jobId: String,
    val campaignId: String,
    val failedClientAttemptId: String,
    val attemptNumber: Int,
    val requestedAt: Long
) {
    init {
        require(action == "safe_retry" || action == "skip_unknown")
        require(attemptNumber > 0)
    }
}
