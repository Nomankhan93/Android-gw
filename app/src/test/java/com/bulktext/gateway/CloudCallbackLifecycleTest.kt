package com.bulktext.gateway

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import com.bulktext.gateway.cloud.GatewayQueueJob
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/** Real BroadcastReceiver lifecycle + SQLite, without real SMS or cloud access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = GatewayApp::class, manifest = Config.NONE)
class CloudCallbackLifecycleTest {
    private lateinit var app: GatewayApp
    private lateinit var attemptId: String
    private val deviceId = "test-device"

    @Before fun prepareAttempt() {
        app = RuntimeEnvironment.getApplication() as GatewayApp
        val job = GatewayQueueJob(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            2, "+923001234567", "callback fixture", "GSM-7", 1,
            UUID.randomUUID().toString(), 1, 1L, 100_000L,
            UUID.randomUUID().toString(), 1, 0, "a".repeat(64)
        )
        app.cloudQueueStore.persist(deviceId, job)
        assertEquals(1, app.cloudQueueStore.markAcknowledged(deviceId, listOf(job.receipt())))
        attemptId = app.cloudQueueStore.ensurePreparedAttempt(deviceId, job.jobId, 1).clientAttemptId
        app.cloudQueueStore.bindServerAttempt(attemptId, 1)
        assertTrue(app.cloudQueueStore.beginSubmission(deviceId, job.jobId, attemptId))
        assertTrue(app.cloudQueueStore.markSubmitted(deviceId, job.jobId))
    }

    private fun callback(code: Int, kind: String = "sent", error: Int? = null): String {
        val event = UUID.randomUUID().toString()
        val action = "com.bulktext.gateway.CLOUD_SMS_${kind.uppercase()}_${attemptId}_0"
        val receiver = CloudSmsResultReceiver()
        val intent = Intent(action).setPackage(app.packageName)
            .putExtra(CloudSmsResultReceiver.EXTRA_CLIENT_ATTEMPT_ID, attemptId)
            .putExtra(CloudSmsResultReceiver.EXTRA_EVENT_ID, event)
            .putExtra(CloudSmsResultReceiver.EXTRA_KIND, kind)
            .putExtra(CloudSmsResultReceiver.EXTRA_PART_INDEX, 0)
        if (error != null) intent.putExtra("errorCode", error)
        app.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            app.sendOrderedBroadcast(intent, null, null, null, code, null, null)
            shadowOf(Looper.getMainLooper()).idle()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                if (app.cloudQueueStore.latestCallbackDiagnostics(deviceId).any { it.kind == kind }) return event
                Thread.sleep(10L)
            }
            fail("Callback was not persisted")
            return event
        } finally { app.unregisterReceiver(receiver) }
    }

    @Test fun sentSuccessSurvivesGoAsyncAndRemainsInOutbox() {
        val event = callback(Activity.RESULT_OK)
        assertEquals("SENT", app.cloudQueueStore.currentAttemptOutcome(attemptId)?.state)
        assertEquals(-1, app.cloudQueueStore.pendingAttemptEvents(deviceId).single { it.event.eventType == "sent" }.event.resultCode)
    }

    @Test fun realFailureAndPlatformErrorCodeArePreservedWithoutRetry() {
        val event = callback(1, error = 42)
        val outcome = app.cloudQueueStore.currentAttemptOutcome(attemptId)!!
        assertEquals("UNKNOWN", outcome.state)
        assertFalse(outcome.safeRetryEligible)
        assertEquals(1, app.cloudQueueStore.pendingAttemptEvents(deviceId).single { it.event.eventType == "sent" }.event.resultCode)
        assertEquals(42, app.cloudQueueStore.latestCallbackDiagnostics(deviceId).first().errorCode)
    }

    @Test fun deliveryResultOkWithoutPduCannotClaimDelivered() {
        callback(Activity.RESULT_OK)
        val event = callback(Activity.RESULT_OK, kind = "delivery")
        assertEquals("SENT", app.cloudQueueStore.currentAttemptOutcome(attemptId)?.state)
        assertTrue(app.cloudQueueStore.pendingAttemptEvents(deviceId).any { it.event.eventType == "delivery" && it.event.deliveryStatus == null })
        assertEquals(-1, app.cloudQueueStore.latestCallbackDiagnostics(deviceId).first { it.kind == "delivery" }.resultCode)
    }

    @Test fun callbackFlagsAllowPlatformExtrasButKeepExplicitTargetingAtCallsite() {
        assertEquals(0, CloudCallbackPolicy.pendingIntentFlags() and PendingIntent.FLAG_IMMUTABLE)
        assertTrue(CloudCallbackPolicy.pendingIntentFlags() and PendingIntent.FLAG_MUTABLE != 0)
    }

    @Test fun actualGsmStatusReportRequiresSuccessfulDeliveryStatus() {
        // SMSC length 0, STATUS-REPORT, MR 0, 11-digit recipient, two timestamps, TP-ST.
        fun report(status: Int): ByteArray {
            val hex = "0002000B912143658709F16210902100000062109021000000" + "%02X".format(status)
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        assertTrue(CloudCallbackPolicy.isConfirmedDelivery(Activity.RESULT_OK, report(0), "3gpp"))
        assertFalse(CloudCallbackPolicy.isConfirmedDelivery(Activity.RESULT_OK, report(0x20), "3gpp"))
        assertFalse(CloudCallbackPolicy.isConfirmedDelivery(Activity.RESULT_OK, report(0x40), "3gpp"))
    }

    @Test fun callbackSyncCanBeScheduledWhileSendingIsDisabled() {
        app.backgroundGatewayPreferences.disable()
        CallbackSyncService.schedule(app)
        val job = requireNotNull(app.getSystemService(android.app.job.JobScheduler::class.java)).allPendingJobs.single { it.id == 184 }
        assertTrue(job.isPersisted)
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertEquals(CallbackSyncService::class.java.name, job.service.className)
        assertFalse(app.backgroundGatewayPreferences.isEnabled())
    }

    @Test fun stopGenerationSurvivesPreferenceObjectRecreation() {
        val prefs = app.backgroundGatewayPreferences
        prefs.enable(100L)
        val permit = prefs.submissionGate.capture(true)!!
        prefs.disable()
        val restored = BackgroundGatewayPreferences(app)
        assertFalse(restored.isEnabled())
        assertFalse(restored.submissionGate.isAllowed(permit))
        restored.enable(200L)
        assertFalse(restored.submissionGate.isAllowed(permit))
    }
}
