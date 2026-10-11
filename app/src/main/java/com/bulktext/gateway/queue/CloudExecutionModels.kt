package com.bulktext.gateway.queue

data class CloudExecutableJob(
    val jobId: String,
    val deviceId: String,
    val dispatchId: String,
    val campaignId: String,
    val phone: String,
    val body: String,
    val expectedSegments: Int,
    val gatewaySimId: String,
    val subscriptionId: Int,
    val slotIndex: Int,
    val simIdentityHash: String,
    val executionState: String,
    val executionNote: String?
)

data class CloudExecutionCounts(
    val ready: Int,
    val submitting: Int,
    val submitted: Int,
    val sent: Int,
    val delivered: Int,
    val failed: Int,
    val unknown: Int,
    val blocked: Int = 0,
    val prepared: Int = 0
)

/**
 * 0.18 execution policy. Cloud attempts are registered before SmsManager,
 * complete SENT callbacks gate certainty, and retries are never automatic.
 */
object CloudExecutionPolicy {
    const val MAX_BATCH_JOBS = 100
    const val BATCH_PACING_MS = 500L
    const val SENT_CALLBACK_TIMEOUT_MS = 60_000L
    const val BACKGROUND_CALLBACK_SWEEP_MS = 120_000L

    const val READY = "READY"
    const val SUBMITTING = "SUBMITTING"
    const val SUBMITTED = "SUBMITTED"
    const val UNKNOWN = "UNKNOWN"

    const val OUTCOME_NONE = "NONE"
    const val OUTCOME_PREPARED = "PREPARED"
    const val OUTCOME_SUBMITTED = "SUBMITTED"
    const val OUTCOME_SENT = "SENT"
    const val OUTCOME_DELIVERED = "DELIVERED"
    const val OUTCOME_FAILED = "FAILED"
    const val OUTCOME_UNKNOWN = "UNKNOWN"
    const val OUTCOME_UNKNOWN_RESOLVED = "UNKNOWN_RESOLVED"

    fun canBegin(acknowledged: Boolean, state: String): Boolean = acknowledged && state == READY
    fun recoverAfterProcessRestart(state: String): String = if (state == SUBMITTING) UNKNOWN else state
    fun afterSmsManagerReturn(): String = SUBMITTED
    fun afterPossibleSmsManagerFailure(): String = UNKNOWN

    fun exactSimMatches(
        job: CloudExecutableJob,
        boundSimId: String,
        boundSubscriptionId: Int,
        boundSlotIndex: Int,
        localIdentityHash: String?
    ): Boolean =
        boundSimId == job.gatewaySimId &&
            boundSubscriptionId == job.subscriptionId &&
            boundSlotIndex == job.slotIndex &&
            localIdentityHash == job.simIdentityHash

    fun segmentCountMatches(expected: Int, actual: Int): Boolean = expected > 0 && expected == actual
    fun boundedBatchSize(readyJobs: Int): Int = readyJobs.coerceIn(0, MAX_BATCH_JOBS)
    fun canContinueBatch(submitting: Int, unknown: Int, processed: Int): Boolean =
        submitting == 0 && unknown == 0 && processed < MAX_BATCH_JOBS

    /**
     * 0.18.1 safety rule: after SmsManager has been invoked, a failed SENT
     * callback is not proof that the carrier/device produced zero external
     * effect. Real-device acceptance showed delivery despite failed callbacks.
     * Therefore callback evidence alone can never authorize a resend.
     */
    fun safeRetryEligible(sentOk: Int, sentFailed: Int, expectedParts: Int, pending: Int): Boolean = false

    fun allSentCallbacksFailed(sentOk: Int, sentFailed: Int, expectedParts: Int, pending: Int): Boolean =
        expectedParts > 0 && pending == 0 && sentOk == 0 && sentFailed == expectedParts

    fun sentAggregate(sentOk: Int, sentFailed: Int, pending: Int, expectedParts: Int): String = when {
        expectedParts <= 0 -> OUTCOME_UNKNOWN
        sentOk == expectedParts -> OUTCOME_SENT
        allSentCallbacksFailed(sentOk, sentFailed, expectedParts, pending) -> OUTCOME_UNKNOWN
        pending == 0 && sentOk > 0 && sentFailed > 0 -> OUTCOME_UNKNOWN
        else -> OUTCOME_SUBMITTED
    }
}
