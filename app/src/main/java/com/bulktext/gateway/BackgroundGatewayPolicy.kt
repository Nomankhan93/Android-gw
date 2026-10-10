package com.bulktext.gateway

object BackgroundGatewayPolicy {
    const val POLL_INTERVAL_MS = 15_000L

    fun eligibleForAutomaticExecution(persistedAt: Long, activatedAt: Long): Boolean =
        activatedAt > 0L && persistedAt >= activatedAt

    fun mustPauseForUnknown(unknownCount: Int): Boolean = unknownCount > 0

    /** UNKNOWN must not prevent starting the service that can receive Web recovery. */
    fun canEnableForRecovery(submittingCount: Int): Boolean = submittingCount == 0

    /** Service stays alive while enabled so Web recovery can be received while paused. */
    fun shouldServiceRun(enabled: Boolean): Boolean = enabled

    /** Irreversible execution is allowed only when the recovery pause is clear. */
    fun canExecute(enabled: Boolean, pausedReason: String?): Boolean = enabled && pausedReason.isNullOrBlank()

    // Backward-compatible helper retained for tests/UI; 0.18 polling is recovery-capable while paused.
    fun canPoll(enabled: Boolean, pausedReason: String?): Boolean = shouldServiceRun(enabled)
}
