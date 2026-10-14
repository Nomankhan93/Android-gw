package com.bulktext.gateway

/** Idle control-plane latency is bounded at 60 seconds; recovery stays responsive. */
class AdaptiveGatewayPolling {
    private var idleCycles = 0
    fun nextDelay(active: Boolean): Long {
        if (active) { idleCycles = 0; return 15_000L }
        idleCycles = (idleCycles + 1).coerceAtMost(3)
        return when (idleCycles) { 1 -> 15_000L; 2 -> 30_000L; else -> 60_000L }
    }
}
