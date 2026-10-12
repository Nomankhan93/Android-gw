package com.bulktext.gateway

import android.content.Context
import android.content.Intent
import com.bulktext.gateway.cloud.GatewayQueueJob
import com.bulktext.gateway.cloud.GatewayRecoveryRequest
import com.bulktext.gateway.queue.CloudExecutableJob
import com.bulktext.gateway.queue.CloudExecutionPolicy
import com.bulktext.gateway.queue.RecoveryApplyResult
import com.bulktext.gateway.queue.SQLiteCloudQueueStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=GatewayApp::class, manifest=Config.NONE)
class ReliabilityContract0186Test {
    private lateinit var context: Context
    private lateinit var store: SQLiteCloudQueueStore
    private lateinit var prefs: BackgroundGatewayPreferences

    private val deviceId = "device-0186-test-111111111111"
    private val campaignId = "campaign-0186-222222222222"
    private val jobId = "job-0186-333333333333"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val app = context as GatewayApp
        app.cloudQueueStore.close()
        context.deleteDatabase("cloud_queue.db")
        store = SQLiteCloudQueueStore(context)
        app.cloudQueueStore = store
        prefs = BackgroundGatewayPreferences(context)
        prefs.disable()
    }

    private fun mockQueueJob(jobIdStr: String = jobId) = GatewayQueueJob(
        jobId = jobIdStr,
        dispatchId = "dispatch-0186",
        campaignId = campaignId,
        sourceRowNumber = 2,
        normalizedE164 = "+923001234567",
        renderedMessage = "0.18.6 reliability test message",
        smsEncoding = "GSM-7",
        segmentCount = 1,
        leaseToken = "lease-token-0186",
        leaseVersion = 1,
        leasedAt = System.currentTimeMillis(),
        leaseExpiresAt = System.currentTimeMillis() + 60000L,
        gatewaySimId = "sim-0186-guid",
        simSubscriptionId = 1,
        simSlotIndex = 0,
        simIdentityHash = "a".repeat(64)
    )

    private fun mockExecutableJob(jobIdStr: String = jobId) = CloudExecutableJob(
        jobId = jobIdStr,
        deviceId = deviceId,
        dispatchId = "dispatch-0186",
        campaignId = campaignId,
        phone = "+923001234567",
        body = "0.18.6 reliability test message",
        expectedSegments = 1,
        gatewaySimId = "sim-0186-guid",
        subscriptionId = 1,
        slotIndex = 0,
        simIdentityHash = "hash0186".repeat(8),
        executionState = CloudExecutionPolicy.READY,
        executionNote = null
    )

    private fun persistAndAckJob(jobIdStr: String = jobId) {
        store.persist(deviceId, mockQueueJob(jobIdStr))
        val receipts = store.pendingAckReceipts(deviceId, 10)
        store.markAcknowledged(deviceId, receipts)
    }

    // Test A & B: Authoritative SMS boundary - Watchdog/Boot receivers have zero SmsManager calls
    @Test
    fun watchdogAndBootReceiversDoNotCallSmsManager() {
        fun findSrc(path: String): File {
            val f1 = File(path)
            if (f1.exists()) return f1
            val f2 = File("app/$path")
            if (f2.exists()) return f2
            return File("../$path")
        }

        val watchdogCode = findSrc("src/main/java/com/bulktext/gateway/GatewayWatchdogService.kt").readText()
        val bootCode = findSrc("src/main/java/com/bulktext/gateway/GatewayBootReceiver.kt").readText()
        val callbackCode = findSrc("src/main/java/com/bulktext/gateway/CloudSmsResultReceiver.kt").readText()
        val bgServiceCode = findSrc("src/main/java/com/bulktext/gateway/BackgroundGatewayService.kt").readText()
        val syncCode = findSrc("src/main/java/com/bulktext/gateway/CallbackSyncService.kt").readText()

        listOf(watchdogCode, bootCode, callbackCode, bgServiceCode, syncCode).forEach { code ->
            assertFalse(code.contains("sendMultipartTextMessage"))
            assertFalse(code.contains("sendTextMessage"))
        }

        val sendingServiceCode = findSrc("src/main/java/com/bulktext/gateway/CloudSendingService.kt").readText()
        assertTrue(sendingServiceCode.contains("manager.sendMultipartTextMessage(current.phone"))
    }

    // Test C: Interrupted SUBMITTING -> UNKNOWN stateful database regression
    @Test
    fun restartPreservesUnknownNoResend() {
        persistAndAckJob()
        val prepared = store.ensurePreparedAttempt(deviceId, jobId, 1)
        store.bindServerAttempt(prepared.clientAttemptId, 1)
        assertTrue(store.beginSubmission(deviceId, jobId, prepared.clientAttemptId))
        assertEquals(1, store.executionCounts(deviceId).submitting)

        // Simulate process crash / store recovery
        val recoveredCount = store.recoverInterruptedSubmissions()
        assertEquals(1, recoveredCount)
        assertEquals(0, store.executionCounts(deviceId).submitting)
        assertEquals(1, store.executionCounts(deviceId).unknown)
        assertNull(store.nextReady(deviceId))
    }

    // Test D: Callback-pending state survives process recovery
    @Test
    fun callbackPendingStateSurvivesRestart() {
        persistAndAckJob()
        val prepared = store.ensurePreparedAttempt(deviceId, jobId, 1)
        store.bindServerAttempt(prepared.clientAttemptId, 1)
        store.beginSubmission(deviceId, jobId, prepared.clientAttemptId)
        store.markSubmitted(deviceId, jobId)

        val eventId = "event-0186-d"
        store.recordAttemptCallback(prepared.clientAttemptId, eventId, 0, "sent", -1)

        // Re-instantiate store on same context/db
        val store2 = SQLiteCloudQueueStore(context)
        val pendingEvents = store2.pendingAttemptEvents(deviceId, 10)
        assertTrue(pendingEvents.isNotEmpty())
        val sentEvent = pendingEvents.firstOrNull { it.event.eventType == "sent" }
        assertNotNull(sentEvent)
        assertEquals(eventId, sentEvent?.event?.eventId)
    }

    // Test E: Recovery ACK pending state survives restart
    @Test
    fun recoveryAckPendingSurvivesRestart() {
        persistAndAckJob()
        val prepared = store.ensurePreparedAttempt(deviceId, jobId, 1)
        store.bindServerAttempt(prepared.clientAttemptId, 1)
        store.beginSubmission(deviceId, jobId, prepared.clientAttemptId)
        store.markSubmissionUnknown(deviceId, jobId, "Ambiguous submission test")

        val req = GatewayRecoveryRequest("rec-0186-e", "skip_unknown", jobId, campaignId, prepared.clientAttemptId, 1, 1000L)
        store.applyRecoveryRequest(deviceId, req)

        // Re-instantiate store on same context/db
        val store2 = SQLiteCloudQueueStore(context)
        val pendingAcks = store2.pendingRecoveryAcks(deviceId)
        assertEquals(1, pendingAcks.size)
        assertEquals("rec-0186-e", pendingAcks.single().first)
    }

    // Test F: Explicit "continue without resend" remains no-resend
    @Test
    fun skipUnknownActionResolvesWithoutResending() {
        persistAndAckJob()
        val prepared = store.ensurePreparedAttempt(deviceId, jobId, 1)
        store.bindServerAttempt(prepared.clientAttemptId, 1)
        store.beginSubmission(deviceId, jobId, prepared.clientAttemptId)
        store.markSubmissionUnknown(deviceId, jobId, "Ambiguous test")

        val req = GatewayRecoveryRequest("rec-0186-f", "skip_unknown", jobId, campaignId, prepared.clientAttemptId, 1, 1000L)
        val result = store.applyRecoveryRequest(deviceId, req)
        assertEquals(RecoveryApplyResult.APPLIED, result)

        assertEquals(0, store.executionCounts(deviceId).unknown)
        assertEquals(0, store.executionCounts(deviceId).ready)
        assertNull(store.nextReady(deviceId))
    }

    // Test G: Exact-SIM mismatch blocks SMS
    @Test
    fun exactSimMismatchBlocksSubmission() {
        val j = mockExecutableJob()
        assertFalse(CloudExecutionPolicy.exactSimMatches(j, boundSimId = "sim-wrong-id", boundSubscriptionId = 1, boundSlotIndex = 0, localIdentityHash = j.simIdentityHash))
        assertFalse(CloudExecutionPolicy.exactSimMatches(j, boundSimId = j.gatewaySimId, boundSubscriptionId = 99, boundSlotIndex = 0, localIdentityHash = j.simIdentityHash))
        assertFalse(CloudExecutionPolicy.exactSimMatches(j, boundSimId = j.gatewaySimId, boundSubscriptionId = 1, boundSlotIndex = 3, localIdentityHash = j.simIdentityHash))
        assertFalse(CloudExecutionPolicy.exactSimMatches(j, boundSimId = j.gatewaySimId, boundSubscriptionId = 1, boundSlotIndex = 0, localIdentityHash = "wrong-hash"))
        assertTrue(CloudExecutionPolicy.exactSimMatches(j, boundSimId = j.gatewaySimId, boundSubscriptionId = 1, boundSlotIndex = 0, localIdentityHash = j.simIdentityHash))
    }

    // Test H: Disabled gateway stays disabled after boot / watchdog run
    @Test
    fun disabledGatewayStaysDisabled() {
        prefs.disable()
        assertFalse(prefs.isEnabled())

        val receiver = GatewayBootReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertFalse(prefs.isEnabled())
    }

    // Test L: Prepared attempt identity across restart
    @Test
    fun restartBetweenBeginAndSubmissionPreservesAttempt() {
        persistAndAckJob()
        val attempt1 = store.ensurePreparedAttempt(deviceId, jobId, 1)

        val store2 = SQLiteCloudQueueStore(context)
        val attempt2 = store2.ensurePreparedAttempt(deviceId, jobId, 1)

        assertEquals(attempt1.clientAttemptId, attempt2.clientAttemptId)
    }

    // Test M: Process restart after SmsManager submission treats state as ambiguous UNKNOWN
    @Test
    fun restartAfterSmsManagerSubmissionIsUnknown() {
        persistAndAckJob()
        val prepared = store.ensurePreparedAttempt(deviceId, jobId, 1)
        store.bindServerAttempt(prepared.clientAttemptId, 1)
        store.beginSubmission(deviceId, jobId, prepared.clientAttemptId)

        // Process crash / store restart
        val store2 = SQLiteCloudQueueStore(context)
        assertEquals(1, store2.recoverInterruptedSubmissions())

        assertEquals(0, store2.executionCounts(deviceId).submitting)
        assertEquals(1, store2.executionCounts(deviceId).unknown)
        assertNull(store2.nextReady(deviceId))
    }
}
