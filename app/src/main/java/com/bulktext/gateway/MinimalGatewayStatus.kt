package com.bulktext.gateway

/** Presentation only: never grants authorization or changes queue state. */
object MinimalGatewayStatus {
    fun label(enabled: Boolean, paired: Boolean, permitted: Boolean, simReady: Boolean,
              paused: Boolean, operationError: Boolean, activated: Long, heartbeat: Long, now: Long): String = when {
        !paired || !permitted -> "Needs setup"
        !enabled -> "Stopped"
        paused -> "Sending paused — review in Web"
        operationError -> "Needs attention — sync will retry"
        heartbeat < activated || heartbeat == 0L -> if (now - activated < 120_000L && now >= activated) "Starting…" else "Needs attention — no recent activity"
        now < heartbeat || now - heartbeat > 120_000L -> "Needs attention — no recent activity"
        !simReady -> "Waiting for selected SIM"
        else -> "Active — recent gateway activity"
    }
}
