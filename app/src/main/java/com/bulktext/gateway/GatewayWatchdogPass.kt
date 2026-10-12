package com.bulktext.gateway

/** Bounded control-plane work; each new action must still belong to the enabled generation. */
class GatewayWatchdogPass(
    private val allowed: () -> Boolean,
    private val flushCallbacks: () -> Unit,
    private val syncRecovery: () -> Unit,
    private val needsRestart: () -> Boolean,
    private val restart: () -> Unit
) {
    fun run() {
        if (!allowed()) return
        try { flushCallbacks() } catch (_: Exception) { /* Repository retains errors/outbox. */ }
        if (!allowed()) return
        try { syncRecovery() } catch (_: Exception) { /* Next scheduled pass can retry. */ }
        if (!allowed()) return
        if (needsRestart() && allowed()) restart()
    }
}

object GatewayWatchdogPolicy {
    const val INTERVAL_MS = 15 * 60 * 1000L
    const val STARTUP_GRACE_MS = 2 * 60 * 1000L

    fun needsRestart(enabled: Boolean, activatedAt: Long, lastCycleAt: Long, now: Long): Boolean {
        if (!enabled) return false
        // A heartbeat from before the latest enable action does not prove this run started.
        if (lastCycleAt <= 0L || lastCycleAt < activatedAt) {
            return activatedAt <= 0L || now < activatedAt || now - activatedAt >= STARTUP_GRACE_MS
        }
        return now < lastCycleAt || now - lastCycleAt >= INTERVAL_MS
    }
}
