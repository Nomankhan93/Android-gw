import com.bulktext.gateway.BackgroundGatewayPolicy

private var checks = 0
private fun ok(value: Boolean, message: String) { check(value) { message }; checks++ }

fun main() {
    // 1. disabling gateway stops background polling
    ok(!BackgroundGatewayPolicy.shouldServiceRun(false), "disabled gateway service stops")
    // 2. queue polling runs without manual button (service stays alive when enabled)
    ok(BackgroundGatewayPolicy.shouldServiceRun(true), "enabled gateway service stays alive")
    // 3. recovery polling runs without manual button (gateway enabled + UNKNOWN => polling continues)
    ok(BackgroundGatewayPolicy.canPoll(true, "UNKNOWN"), "gateway enabled + UNKNOWN => polling/recovery continues")
    ok(BackgroundGatewayPolicy.shouldServiceRun(true), "gateway enabled + UNKNOWN => service stays running")
    // 4. gateway enabled + UNKNOWN => SMS execution does not continue
    ok(!BackgroundGatewayPolicy.canExecute(true, "UNKNOWN"), "gateway enabled + UNKNOWN => SMS execution does not continue")

    ok(BackgroundGatewayPolicy.canEnableForRecovery(0), "UNKNOWN does not block starting recovery service")
    ok(!BackgroundGatewayPolicy.canEnableForRecovery(1), "active submission still blocks service toggle")
    ok(BackgroundGatewayPolicy.canExecute(true, null), "clean enabled gateway can execute")
    ok(!BackgroundGatewayPolicy.canExecute(false, null), "disabled gateway cannot execute")
    ok(BackgroundGatewayPolicy.mustPauseForUnknown(1), "UNKNOWN pauses automation")
    ok(!BackgroundGatewayPolicy.mustPauseForUnknown(0), "clean queue does not pause")
    ok(BackgroundGatewayPolicy.eligibleForAutomaticExecution(2_000, 1_000), "post-activation job is eligible")
    ok(!BackgroundGatewayPolicy.eligibleForAutomaticExecution(999, 1_000), "pre-activation job is not auto-executed")
    ok(BackgroundGatewayPolicy.POLL_INTERVAL_MS >= 10_000L, "polling is bounded")
    println("PASS: Background gateway core $checks checks")
}
