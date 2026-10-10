package com.bulktext.gateway.queue

import com.bulktext.gateway.cloud.GatewayAttemptEvent

data class LocalCloudAttempt(
    val clientAttemptId: String,
    val jobId: String,
    val expectedParts: Int,
    val attemptNumber: Int?,
    val state: String,
    val safeRetryEligible: Boolean
)

data class LocalAttemptOutcome(
    val state: String,
    val safeRetryEligible: Boolean,
    val note: String?
)

data class PendingAttemptEvent(
    val event: GatewayAttemptEvent,
    val attempts: Int
)


data class CallbackDiagnostic(
    val clientAttemptId: String,
    val jobId: String,
    val partIndex: Int,
    val kind: String,
    val resultCode: Int,
    val symbolicResult: String,
    val errorCode: Int?,
    val duplicateCount: Int,
    val elapsedMs: Long?,
    val occurredAt: Long
)

enum class RecoveryApplyResult { APPLIED, ALREADY_APPLIED, REJECTED_UNSAFE, NOT_APPLICABLE }
