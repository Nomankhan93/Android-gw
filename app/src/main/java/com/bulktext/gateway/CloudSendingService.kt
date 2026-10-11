package com.bulktext.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import com.bulktext.gateway.inventory.GatewayInventoryState
import com.bulktext.gateway.queue.CloudExecutableJob
import com.bulktext.gateway.queue.CloudExecutionPolicy
import java.util.UUID

/**
 * Android 0.18 callback-aware campaign execution boundary.
 *
 * Every attempt is registered in cloud before SmsManager. Per-part SENT and
 * DELIVERED callbacks are persisted locally first. UNKNOWN and mixed SENT
 * results stop the batch. Callback-derived retries are disabled;
 * no automatic resend exists here.
 */
class CloudSendingService : Service() {
    private val app get() = application as GatewayApp

    @Volatile private var stopping = false
    @Volatile private var activeCampaignId: String? = null
    @Volatile private var activeJobId: String? = null
    @Volatile private var submissionBoundaryPersisted = false

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() { stopping = true; super.onDestroy() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || !intent.hasExtra(EXTRA_STOP_GENERATION) || !intent.hasExtra(EXTRA_AUTOMATIC)) {
            stopSelf(startId); return START_NOT_STICKY
        }
        val permit = CloudSendPermit(intent.getLongExtra(EXTRA_STOP_GENERATION, -1L), intent.getBooleanExtra(EXTRA_AUTOMATIC, true))
        if (!app.backgroundGatewayPreferences.submissionGate.isAllowed(permit) || stopping) {
            stopSelf(startId); return START_NOT_STICKY
        }
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        var campaignId = intent?.getStringExtra(EXTRA_CAMPAIGN_ID)
        val legacyJobId = intent?.getStringExtra(EXTRA_JOB_ID)
        val notBeforeMillis = intent?.getLongExtra(EXTRA_NOT_BEFORE, 0L) ?: 0L
        if (deviceId.isNullOrBlank()) { stopSelf(startId); return START_NOT_STICKY }
        if (campaignId.isNullOrBlank() && !legacyJobId.isNullOrBlank()) campaignId = app.cloudQueueStore.getExecutable(deviceId, legacyJobId)?.campaignId
        if (campaignId.isNullOrBlank()) { stopSelf(startId); return START_NOT_STICKY }
        val resolvedCampaignId = requireNotNull(campaignId)
        if (activeCampaignId != null) return START_NOT_STICKY

        activeCampaignId = resolvedCampaignId
        showNotification("Registering attempts and waiting for SENT callbacks")
        Thread({ executeCampaign(deviceId, resolvedCampaignId, notBeforeMillis, permit) }, "BulkTextCloud018").start()
        return START_NOT_STICKY
    }

    private fun executeCampaign(deviceId: String, campaignId: String, notBeforeMillis: Long, permit: CloudSendPermit) {
        var processed = 0
        try {
            while (!stopping && app.backgroundGatewayPreferences.submissionGate.isAllowed(permit)) {
                val counts = app.cloudQueueStore.executionCounts(deviceId)
                if (!CloudExecutionPolicy.canContinueBatch(counts.submitting, counts.unknown, processed)) break
                val job = if (notBeforeMillis > 0L) app.cloudQueueStore.nextReadyForCampaignSince(deviceId, campaignId, notBeforeMillis)
                    else app.cloudQueueStore.nextReadyForCampaign(deviceId, campaignId)
                if (job == null || !executeOne(deviceId, job, permit)) break
                processed += 1
                if (processed < CloudExecutionPolicy.MAX_BATCH_JOBS) Thread.sleep(CloudExecutionPolicy.BATCH_PACING_MS)
            }
        } finally {
            try { app.cloudDeliveryRepository.flushEvents() } catch (_: Exception) { }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            activeCampaignId = null
            activeJobId = null
            submissionBoundaryPersisted = false
        }
    }

    private fun executeOne(deviceId: String, job: CloudExecutableJob, permit: CloudSendPermit): Boolean {
        activeJobId = job.jobId
        submissionBoundaryPersisted = false
        var clientAttemptId: String? = null
        return try {
            if (stopping || !app.backgroundGatewayPreferences.submissionGate.isAllowed(permit)) return false
            val pairedId = app.secureCredentialStore.getDeviceId()
            require(pairedId == deviceId) { "Cloud job does not belong to this paired gateway identity." }
            require(Telephony.permitted(this)) { "SMS/phone permission missing. No SMS was submitted." }

            val counts = app.cloudQueueStore.executionCounts(deviceId)
            require(counts.submitting == 0) { "Another cloud SMS submission is already in progress." }
            require(counts.unknown == 0) { "A prior cloud SMS is UNKNOWN and requires operator recovery before another send." }

            val current = app.cloudQueueStore.getExecutable(deviceId, job.jobId)
                ?: error("Cloud job is not locally executable or is no longer READY.")
            require(current.executionState == CloudExecutionPolicy.READY) { "Cloud job is ${current.executionState}; it will not be submitted again." }

            val snapshot = app.inventoryProvider.snapshot()
            val inventory = app.inventoryRepository.report(snapshot)
            val ready = inventory as? GatewayInventoryState.Ready
                ?: throw IllegalStateException("Exact Web-selected SIM is not Ready. No SMS was submitted.")
            val binding = ready.binding
            val localIdentityHash = snapshot.sims.firstOrNull { it.subscriptionId == current.subscriptionId && it.slotIndex == current.slotIndex }?.simIdentityHash
            if (!CloudExecutionPolicy.exactSimMatches(current, binding.simId, binding.subscriptionId, binding.slotIndex, localIdentityHash)) {
                app.cloudQueueStore.blockBeforeSubmission(deviceId, current.jobId, "sim_gate")
                return true
            }

            val manager = Telephony.manager(this, current.subscriptionId)
            val parts = manager.divideMessage(current.body)
            if (!CloudExecutionPolicy.segmentCountMatches(current.expectedSegments, parts.size)) {
                app.cloudQueueStore.blockBeforeSubmission(deviceId, current.jobId, "segment_mismatch")
                return true
            }

            // Durable local attempt, then authoritative cloud registration. If cloud
            // is unavailable, execution stops before SmsManager and remains safe.
            val localAttempt = app.cloudQueueStore.ensurePreparedAttempt(deviceId, current.jobId, parts.size)
            clientAttemptId = localAttempt.clientAttemptId
            val grant = app.cloudDeliveryRepository.beginAttempt(current.jobId, localAttempt)
            if (grant.outcome != "registered" || !grant.canSubmit) return true // skip blocked job; never cross SMS boundary

            val sentIntents = ArrayList<PendingIntent>(parts.size)
            val deliveryIntents = ArrayList<PendingIntent>(parts.size)
            for (index in parts.indices) {
                sentIntents += callbackPendingIntent(localAttempt.clientAttemptId, index, CloudSmsResultReceiver.KIND_SENT)
                deliveryIntents += callbackPendingIntent(localAttempt.clientAttemptId, index, CloudSmsResultReceiver.KIND_DELIVERY)
            }

            // Disable and the final SMS boundary share one short lock. Network work
            // above is outside it. Once disable returns, old permits cannot submit.
            val submitted = app.backgroundGatewayPreferences.submissionGate.runIfAllowed(permit) {
                check(!stopping) { "Cloud sending service stopped before submission." }
                check(app.secureCredentialStore.getDeviceId() == deviceId) { "Pairing changed before submission" }
                require(app.cloudQueueStore.beginSubmission(deviceId, current.jobId, localAttempt.clientAttemptId)) {
                    "Cloud job could not enter SUBMITTING; it will not be sent."
                }
                submissionBoundaryPersisted = true
                manager.sendMultipartTextMessage(current.phone, null, parts, sentIntents, deliveryIntents)
            }
            if (!submitted) {
                app.cloudQueueStore.noteReadyBlock(deviceId, current.jobId, "Operator stopped sending before SmsManager; no SMS submitted.")
                return false
            }

            if (!app.cloudQueueStore.markSubmitted(deviceId, current.jobId)) {
                throw IllegalStateException("SmsManager returned but SUBMITTED state could not be durably recorded")
            }
            submissionBoundaryPersisted = false
            try { app.cloudDeliveryRepository.flushEvents() } catch (_: Exception) { /* local outbox remains durable */ }

            waitForSentOutcome(deviceId, current.jobId, localAttempt.clientAttemptId)
        } catch (e: Exception) {
            try {
                if (submissionBoundaryPersisted && !clientAttemptId.isNullOrBlank()) {
                    app.cloudQueueStore.markSubmissionUnknown(
                        deviceId, job.jobId,
                        "SmsManager boundary ended with ${e.javaClass.simpleName}; submission may have occurred. Never auto-retry."
                    )
                    try { app.cloudDeliveryRepository.flushEvents() } catch (_: Exception) { }
                } else {
                    app.cloudQueueStore.noteReadyBlock(deviceId, job.jobId, e.message ?: "Cloud execution was blocked before SmsManager")
                }
            } catch (_: Exception) { /* persisted SUBMITTING is recovered as UNKNOWN on restart */ }
            false
        } finally {
            activeJobId = null
            submissionBoundaryPersisted = false
        }
    }

    private fun waitForSentOutcome(deviceId: String, jobId: String, clientAttemptId: String): Boolean {
        val deadline = System.currentTimeMillis() + CloudExecutionPolicy.SENT_CALLBACK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val outcome = app.cloudQueueStore.currentAttemptOutcome(clientAttemptId)
            when (outcome?.state) {
                CloudExecutionPolicy.OUTCOME_SENT,
                CloudExecutionPolicy.OUTCOME_DELIVERED,
                CloudExecutionPolicy.OUTCOME_FAILED -> {
                    try { app.cloudDeliveryRepository.flushEvents() } catch (_: Exception) { }
                    return true
                }
                CloudExecutionPolicy.OUTCOME_UNKNOWN -> {
                    try { app.cloudDeliveryRepository.flushEvents() } catch (_: Exception) { }
                    return false
                }
            }
            Thread.sleep(250L)
        }
        app.cloudQueueStore.markAttemptCallbackTimeout(
            deviceId, jobId, clientAttemptId,
            "Complete SENT callbacks were not observed within ${CloudExecutionPolicy.SENT_CALLBACK_TIMEOUT_MS / 1000}s after SmsManager submission. No automatic retry."
        )
        try { app.cloudDeliveryRepository.flushEvents() } catch (_: Exception) { }
        return false
    }

    private fun callbackPendingIntent(clientAttemptId: String, partIndex: Int, kind: String): PendingIntent {
        val eventId = UUID.randomUUID().toString()
        val intent = Intent(this, CloudSmsResultReceiver::class.java)
            .setAction("com.bulktext.gateway.CLOUD_SMS_${kind.uppercase()}_${clientAttemptId}_${partIndex}")
            .putExtra(CloudSmsResultReceiver.EXTRA_CLIENT_ATTEMPT_ID, clientAttemptId)
            .putExtra(CloudSmsResultReceiver.EXTRA_EVENT_ID, eventId)
            .putExtra(CloudSmsResultReceiver.EXTRA_PART_INDEX, partIndex)
            .putExtra(CloudSmsResultReceiver.EXTRA_KIND, kind)
        val requestCode = partIndex * 10 + if (kind == CloudSmsResultReceiver.KIND_SENT) 1 else 2
        return PendingIntent.getBroadcast(this, requestCode, intent, CloudCallbackPolicy.pendingIntentFlags())
    }

    private fun showNotification(text: String) {
        val nm = requireNotNull(getSystemService(NotificationManager::class.java))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Cloud SMS execution", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("BulkText campaign send")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        const val EXTRA_STOP_GENERATION = "stop_generation"
        const val EXTRA_AUTOMATIC = "automatic"
        const val EXTRA_DEVICE_ID = "device"
        const val EXTRA_JOB_ID = "job"
        const val EXTRA_CAMPAIGN_ID = "campaign"
        const val EXTRA_NOT_BEFORE = "not_before"
        private const val CHANNEL_ID = "cloud_sending_0180"
        private const val NOTIFICATION_ID = 181
    }
}
