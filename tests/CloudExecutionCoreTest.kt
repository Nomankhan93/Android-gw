import com.bulktext.gateway.queue.CloudExecutableJob
import com.bulktext.gateway.queue.CloudExecutionPolicy

private var checks = 0
private fun ok(value: Boolean, message: String) { check(value) { message }; checks++ }
private fun eq(actual: Any, expected: Any, message: String) { check(actual == expected) { "$message: expected=$expected actual=$actual" }; checks++ }

fun main() {
    val job = CloudExecutableJob(
        jobId = "job", deviceId = "device", dispatchId = "dispatch", campaignId = "campaign",
        phone = "+923001234567", body = "hello", expectedSegments = 1,
        gatewaySimId = "sim", subscriptionId = 11, slotIndex = 0,
        simIdentityHash = "a".repeat(64), executionState = CloudExecutionPolicy.READY, executionNote = null
    )

    ok(CloudExecutionPolicy.canBegin(true, CloudExecutionPolicy.READY), "ACKed READY can begin")
    ok(!CloudExecutionPolicy.canBegin(false, CloudExecutionPolicy.READY), "unACKed READY cannot begin")
    ok(!CloudExecutionPolicy.canBegin(true, CloudExecutionPolicy.SUBMITTING), "SUBMITTING cannot begin again")
    ok(!CloudExecutionPolicy.canBegin(true, CloudExecutionPolicy.SUBMITTED), "SUBMITTED cannot begin again")
    ok(!CloudExecutionPolicy.canBegin(true, CloudExecutionPolicy.UNKNOWN), "UNKNOWN cannot begin again")
    eq(CloudExecutionPolicy.recoverAfterProcessRestart(CloudExecutionPolicy.SUBMITTING), CloudExecutionPolicy.UNKNOWN, "restart recovery")
    eq(CloudExecutionPolicy.afterSmsManagerReturn(), CloudExecutionPolicy.SUBMITTED, "normal return")
    eq(CloudExecutionPolicy.afterPossibleSmsManagerFailure(), CloudExecutionPolicy.UNKNOWN, "ambiguous failure")
    ok(CloudExecutionPolicy.exactSimMatches(job, "sim", 11, 0, "a".repeat(64)), "exact SIM match")
    ok(!CloudExecutionPolicy.exactSimMatches(job, "other", 11, 0, "a".repeat(64)), "SIM id mismatch")
    ok(!CloudExecutionPolicy.exactSimMatches(job, "sim", 22, 0, "a".repeat(64)), "subscription mismatch")
    ok(!CloudExecutionPolicy.exactSimMatches(job, "sim", 11, 1, "a".repeat(64)), "slot mismatch")
    ok(!CloudExecutionPolicy.exactSimMatches(job, "sim", 11, 0, "b".repeat(64)), "identity mismatch")
    ok(CloudExecutionPolicy.segmentCountMatches(3, 3), "segment count match")
    ok(!CloudExecutionPolicy.segmentCountMatches(3, 2), "segment count mismatch")
    eq(CloudExecutionPolicy.boundedBatchSize(1000), CloudExecutionPolicy.MAX_BATCH_JOBS, "bounded batch")
    ok(!CloudExecutionPolicy.canContinueBatch(0, 1, 0), "unknown stops batch")

    ok(!CloudExecutionPolicy.safeRetryEligible(0, 3, 3, 0), "post-SmsManager callback failure is never retry-safe")
    ok(!CloudExecutionPolicy.safeRetryEligible(1, 2, 3, 0), "mixed SENT result is never retry-safe")
    ok(!CloudExecutionPolicy.safeRetryEligible(0, 2, 3, 1), "pending SENT callback is not retry-safe")
    ok(CloudExecutionPolicy.allSentCallbacksFailed(0, 3, 3, 0), "all SENT failed is detected as ambiguous callback evidence")
    eq(CloudExecutionPolicy.sentAggregate(3, 0, 0, 3), CloudExecutionPolicy.OUTCOME_SENT, "all SENT success")
    eq(CloudExecutionPolicy.sentAggregate(0, 3, 0, 3), CloudExecutionPolicy.OUTCOME_UNKNOWN, "all SENT failed after SmsManager is UNKNOWN")
    eq(CloudExecutionPolicy.sentAggregate(1, 2, 0, 3), CloudExecutionPolicy.OUTCOME_UNKNOWN, "mixed SENT is UNKNOWN")
    eq(CloudExecutionPolicy.sentAggregate(0, 2, 1, 3), CloudExecutionPolicy.OUTCOME_SUBMITTED, "pending callback remains submitted")
    ok(CloudExecutionPolicy.SENT_CALLBACK_TIMEOUT_MS >= 30_000L, "callback wait is bounded but not tight")
    println("PASS: Cloud execution core $checks checks")
}
