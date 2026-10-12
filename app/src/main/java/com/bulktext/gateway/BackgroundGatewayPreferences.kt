package com.bulktext.gateway

import android.content.Context

class BackgroundGatewayPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("bulktext_background_gateway", Context.MODE_PRIVATE)

    val submissionGate = CloudSubmissionGate(
        generation = { prefs.getLong(KEY_SEND_GENERATION, 0L) },
        enabled = { isEnabled() }
    )

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    fun activatedAt(): Long = prefs.getLong(KEY_ACTIVATED_AT, 0L)
    fun pausedReason(): String? = prefs.getString(KEY_PAUSED_REASON, null)
    fun lastStatus(): String? = prefs.getString(KEY_LAST_STATUS, null)
    fun lastControlCycleAt(): Long = prefs.getLong(KEY_LAST_CONTROL_CYCLE_AT, 0L)
    fun lastCallbackFlushAt(): Long = prefs.getLong(KEY_LAST_CALLBACK_FLUSH_AT, 0L)
    fun lastRecoverySyncAt(): Long = prefs.getLong(KEY_LAST_RECOVERY_SYNC_AT, 0L)

    fun enable(now: Long = System.currentTimeMillis()) = submissionGate.changeAuthorization {
        check(prefs.edit()
            .putBoolean(KEY_ENABLED, true)
            .putLong(KEY_SEND_GENERATION, prefs.getLong(KEY_SEND_GENERATION, 0L) + 1L)
            .putLong(KEY_ACTIVATED_AT, now)
            .remove(KEY_PAUSED_REASON)
            .putString(KEY_LAST_STATUS, "Gateway active. Waiting for Web-authorized campaigns.")
            .commit()) { "Could not persist gateway enable state. Try again." }
    }

    fun disable() = submissionGate.changeAuthorization {
        check(prefs.edit()
            .putBoolean(KEY_ENABLED, false)
            .putLong(KEY_SEND_GENERATION, prefs.getLong(KEY_SEND_GENERATION, 0L) + 1L)
            .remove(KEY_PAUSED_REASON)
            .putString(KEY_LAST_STATUS, "Sending stopped before the next recipient. In-flight callbacks may still settle.")
            .commit()) { "Could not persist gateway stop state. Try again." }
        GatewayWatchdogService.cancel(appContext)
    }

    fun pause(reason: String) {
        prefs.edit()
            .putString(KEY_PAUSED_REASON, reason.take(500))
            .putString(KEY_LAST_STATUS, reason.take(500))
            .apply()
    }

    fun clearPause(status: String = "Gateway active. Recovery applied; waiting for Web-authorized campaigns.") {
        prefs.edit()
            .remove(KEY_PAUSED_REASON)
            .putString(KEY_LAST_STATUS, status.take(500))
            .apply()
    }

    fun setStatus(status: String) {
        prefs.edit().putString(KEY_LAST_STATUS, status.take(500)).apply()
    }

    fun markControlCycle(at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_CONTROL_CYCLE_AT, at).apply()
    }

    fun markCallbackFlush(at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_CALLBACK_FLUSH_AT, at).apply()
    }

    fun markRecoverySync(at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_RECOVERY_SYNC_AT, at).apply()
    }

    // Per-device, per-operation state: successful queue polling cannot erase callback/RPC errors.
    @Synchronized
    fun recordOperation(deviceId: String, operation: String, error: Exception? = null, at: Long = System.currentTimeMillis()) {
        require(operation in listOf("begin", "callbacks", "recovery"))
        val key = "operation.$deviceId.$operation"
        val edit = prefs.edit().putLong("$key.attempt", at)
        if (error == null) edit.remove("$key.error").putLong("$key.success", at)
        else edit.putString("$key.error", when (error) {
            is com.bulktext.gateway.cloud.CloudException.NetworkException -> "Network unavailable; retry pending"
            is com.bulktext.gateway.cloud.CloudException.CredentialRejectedException -> "Gateway credential rejected"
            is com.bulktext.gateway.cloud.CloudException.ServerContractException -> "Server response incompatible; update required"
            else -> "Operation failed; retry pending"
        })
        edit.apply()
    }

    fun operationError(deviceId: String, operation: String): String? = prefs.getString("operation.$deviceId.$operation.error", null)
    fun operationSuccessAt(deviceId: String, operation: String): Long = prefs.getLong("operation.$deviceId.$operation.success", 0L)
    fun operationErrors(deviceId: String): String = listOf("begin", "callbacks", "recovery").mapNotNull { op ->
        operationError(deviceId, op)?.let { "$op: $it" }
    }.joinToString("\n")

    companion object {
        private const val KEY_SEND_GENERATION = "send_generation"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ACTIVATED_AT = "activated_at"
        private const val KEY_PAUSED_REASON = "paused_reason"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_LAST_CONTROL_CYCLE_AT = "last_control_cycle_at"
        private const val KEY_LAST_CALLBACK_FLUSH_AT = "last_callback_flush_at"
        private const val KEY_LAST_RECOVERY_SYNC_AT = "last_recovery_sync_at"
    }
}
