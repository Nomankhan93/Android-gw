package com.bulktext.gateway

import com.bulktext.gateway.queue.CloudExecutableJob
import com.bulktext.gateway.queue.CloudExecutionPolicy
import org.junit.Assert.*
import org.junit.Test

class GatewayCloudExecutionTest {
    private fun job(state: String = CloudExecutionPolicy.READY) = CloudExecutableJob(
        jobId = "11111111-1111-4111-8111-111111111111",
        deviceId = "22222222-2222-4222-8222-222222222222",
        dispatchId = "33333333-3333-4333-8333-333333333333",
        campaignId = "44444444-4444-4444-8444-444444444444",
        phone = "+923001234567",
        body = "BulkText cloud execution test",
        expectedSegments = 1,
        gatewaySimId = "55555555-5555-4555-8555-555555555555",
        subscriptionId = 11,
        slotIndex = 0,
        simIdentityHash = "a".repeat(64),
        executionState = state,
        executionNote = null
    )

    @Test fun onlyAckedReadyJobCanBegin() {
        assertTrue(CloudExecutionPolicy.canBegin(true, CloudExecutionPolicy.READY))
        assertFalse(CloudExecutionPolicy.canBegin(false, CloudExecutionPolicy.READY))
        assertFalse(CloudExecutionPolicy.canBegin(true, CloudExecutionPolicy.SUBMITTING))
        assertFalse(CloudExecutionPolicy.canBegin(true, CloudExecutionPolicy.SUBMITTED))
        assertFalse(CloudExecutionPolicy.canBegin(true, CloudExecutionPolicy.UNKNOWN))
    }

    @Test fun interruptedSubmittingRecoversUnknown() {
        assertEquals(CloudExecutionPolicy.UNKNOWN, CloudExecutionPolicy.recoverAfterProcessRestart(CloudExecutionPolicy.SUBMITTING))
        assertEquals(CloudExecutionPolicy.SUBMITTED, CloudExecutionPolicy.recoverAfterProcessRestart(CloudExecutionPolicy.SUBMITTED))
    }

    @Test fun normalSmsManagerReturnMeansSubmittedOnly() {
        assertEquals(CloudExecutionPolicy.SUBMITTED, CloudExecutionPolicy.afterSmsManagerReturn())
    }

    @Test fun possibleSmsManagerFailureMeansUnknown() {
        assertEquals(CloudExecutionPolicy.UNKNOWN, CloudExecutionPolicy.afterPossibleSmsManagerFailure())
    }

    @Test fun exactSimRequiresAllFrozenIdentityFields() {
        val j = job()
        assertTrue(CloudExecutionPolicy.exactSimMatches(j, j.gatewaySimId, 11, 0, "a".repeat(64)))
        assertFalse(CloudExecutionPolicy.exactSimMatches(j, "different", 11, 0, "a".repeat(64)))
        assertFalse(CloudExecutionPolicy.exactSimMatches(j, j.gatewaySimId, 22, 0, "a".repeat(64)))
        assertFalse(CloudExecutionPolicy.exactSimMatches(j, j.gatewaySimId, 11, 1, "a".repeat(64)))
        assertFalse(CloudExecutionPolicy.exactSimMatches(j, j.gatewaySimId, 11, 0, "b".repeat(64)))
    }

    @Test fun otherSimNeverQualifiesAsFallback() {
        val j = job()
        assertFalse(CloudExecutionPolicy.exactSimMatches(j, "66666666-6666-4666-8666-666666666666", 22, 1, "b".repeat(64)))
    }

    @Test fun segmentCountMustRemainExact() {
        assertTrue(CloudExecutionPolicy.segmentCountMatches(1, 1))
        assertTrue(CloudExecutionPolicy.segmentCountMatches(4, 4))
        assertFalse(CloudExecutionPolicy.segmentCountMatches(4, 3))
        assertFalse(CloudExecutionPolicy.segmentCountMatches(0, 0))
    }

    @Test fun campaignBatchIsBoundedAndStopsOnUnknown() {
        assertEquals(0, CloudExecutionPolicy.boundedBatchSize(0))
        assertEquals(3, CloudExecutionPolicy.boundedBatchSize(3))
        assertEquals(CloudExecutionPolicy.MAX_BATCH_JOBS, CloudExecutionPolicy.boundedBatchSize(1000))
        assertTrue(CloudExecutionPolicy.canContinueBatch(0, 0, 0))
        assertFalse(CloudExecutionPolicy.canContinueBatch(1, 0, 0))
        assertFalse(CloudExecutionPolicy.canContinueBatch(0, 1, 0))
        assertFalse(CloudExecutionPolicy.canContinueBatch(0, 0, CloudExecutionPolicy.MAX_BATCH_JOBS))
    }

    @Test fun terminalStatesAreNotReadyAgain() {
        assertFalse(CloudExecutionPolicy.canBegin(true, CloudExecutionPolicy.SUBMITTED))
        assertFalse(CloudExecutionPolicy.canBegin(true, CloudExecutionPolicy.UNKNOWN))
    }

    @Test fun backgroundGatewayStaysOnlineForRecoveryButDoesNotExecuteWhilePaused() {
        assertFalse(BackgroundGatewayPolicy.shouldServiceRun(false))
        assertTrue(BackgroundGatewayPolicy.shouldServiceRun(true))
        assertTrue(BackgroundGatewayPolicy.canPoll(true, "UNKNOWN"))
        assertTrue(BackgroundGatewayPolicy.canEnableForRecovery(0))
        assertFalse(BackgroundGatewayPolicy.canEnableForRecovery(1))
        assertFalse(BackgroundGatewayPolicy.canExecute(true, "UNKNOWN"))
        assertTrue(BackgroundGatewayPolicy.canExecute(true, null))
    }

    @Test fun safeRetryRequiresEverySentPartToFailWithZeroSuccess() {
        assertFalse(CloudExecutionPolicy.safeRetryEligible(0, 3, 3, 0))
        assertFalse(CloudExecutionPolicy.safeRetryEligible(1, 2, 3, 0))
        assertFalse(CloudExecutionPolicy.safeRetryEligible(0, 2, 3, 1))
        assertTrue(CloudExecutionPolicy.allSentCallbacksFailed(0, 3, 3, 0))
        assertEquals(CloudExecutionPolicy.OUTCOME_UNKNOWN, CloudExecutionPolicy.sentAggregate(0, 3, 0, 3))
        assertEquals(CloudExecutionPolicy.OUTCOME_UNKNOWN, CloudExecutionPolicy.sentAggregate(1, 2, 0, 3))
        assertEquals(CloudExecutionPolicy.OUTCOME_SENT, CloudExecutionPolicy.sentAggregate(3, 0, 0, 3))
    }

    @Test fun backgroundGatewayNeverAutoExecutesPreActivationJobs() {
        assertTrue(BackgroundGatewayPolicy.eligibleForAutomaticExecution(2_000, 1_000))
        assertTrue(BackgroundGatewayPolicy.eligibleForAutomaticExecution(1_000, 1_000))
        assertFalse(BackgroundGatewayPolicy.eligibleForAutomaticExecution(999, 1_000))
        assertFalse(BackgroundGatewayPolicy.eligibleForAutomaticExecution(2_000, 0))
    }

    @Test fun backgroundGatewayPausesOnUnknown() {
        assertFalse(BackgroundGatewayPolicy.mustPauseForUnknown(0))
        assertTrue(BackgroundGatewayPolicy.mustPauseForUnknown(1))
    }
}
