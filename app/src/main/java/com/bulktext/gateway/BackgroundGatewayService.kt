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
import com.bulktext.gateway.queue.CloudExecutionPolicy
import com.bulktext.gateway.queue.CloudQueueExecutionIdentity
import com.bulktext.gateway.queue.CloudQueueState

/**
 * 0.18.3 persistent gateway loop.
 *
 * While enabled, the service keeps polling even in UNKNOWN pause mode. Paused
 * mode is recovery-only: callback outbox + explicit Web recovery requests may be
 * processed, but no new SmsManager execution can start until UNKNOWN is cleared.
 */
class BackgroundGatewayService : Service() {
    private val app get() = application as GatewayApp
    @Volatile private var workerRunning = false
    @Volatile private var stopping = false
    private val polling = AdaptiveGatewayPolling()

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BackgroundGatewayPolicy.shouldServiceRun(app.backgroundGatewayPreferences.isEnabled())) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY
        }
        showNotification(app.backgroundGatewayPreferences.pausedReason() ?: "Gateway active • waiting for campaigns")
        if (!workerRunning) {
            workerRunning = true
            GatewayWatchdogService.schedule(this)
            Thread({ runLoop() }, "BulkTextBackgroundGateway0186").start()
        }
        return START_STICKY
    }

    override fun onDestroy() { stopping = true; workerRunning = false; super.onDestroy() }

    private fun runLoop() {
        try {
            while (!stopping && BackgroundGatewayPolicy.shouldServiceRun(app.backgroundGatewayPreferences.isEnabled())) {
                try {
                    runCycle()
                    app.backgroundGatewayPreferences.markControlCycle()
                } catch (e: Exception) {
                    // A transient control-plane failure must not silently kill the persistent worker.
                    // Keep the service alive and retry on the next bounded cycle.
                    updateStatus("Gateway sync will retry automatically (${e.javaClass.simpleName}).")
                }
                val active = try {
                    val device = app.secureCredentialStore.getDeviceId()
                    val counts = device?.let { app.cloudQueueStore.executionCounts(it) }
                    counts == null || counts.ready > 0 || counts.submitting > 0 || counts.submitted > 0 || counts.prepared > 0 || counts.unknown > 0 ||
                        (device != null && app.cloudQueueStore.pendingAttemptEvents(device, 1).isNotEmpty())
                } catch (_: Exception) { true }
                var remaining = polling.nextDelay(active)
                while (!stopping && remaining > 0L) {
                    val slice = minOf(1_000L, remaining)
                    Thread.sleep(slice)
                    remaining -= slice
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            workerRunning = false
            if (!app.backgroundGatewayPreferences.isEnabled()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }

    private fun runCycle() {
        val prefs = app.backgroundGatewayPreferences
        val permit = prefs.submissionGate.capture(automatic = true) ?: return
        if (stopping) return
        val deviceId = app.secureCredentialStore.getDeviceId()
        if (deviceId.isNullOrBlank()) { updateStatus("Waiting: pair this phone with BulkText Web."); return }

        // Callback truth is durable locally. Cloud reporting can be retried safely.
        try { app.cloudDeliveryRepository.flushEvents(); prefs.markCallbackFlush() } catch (_: Exception) { }
        try { app.cloudQueueStore.recoverCallbackTimeouts(deviceId, System.currentTimeMillis() - CloudExecutionPolicy.BACKGROUND_CALLBACK_SWEEP_MS) } catch (_: Exception) { }
        try { app.cloudDeliveryRepository.flushEvents(); prefs.markCallbackFlush() } catch (_: Exception) { }

        // Explicit Web recovery is polled even while UNKNOWN has paused execution.
        try {
            app.cloudDeliveryRepository.syncRecoveryRequests()
            prefs.markRecoverySync()
        } catch (_: Exception) {
            updateStatus("Waiting for network to confirm recovery before any retry can execute.")
        }

        val execution = app.cloudQueueStore.executionCounts(deviceId)
        if (BackgroundGatewayPolicy.mustPauseForUnknown(execution.unknown)) {
            val reason = "Gateway recovery mode: an SMS is UNKNOWN. Sync stays active; sending is paused until explicit Web recovery."
            prefs.pause(reason)
            showNotification(reason)
        } else if (!prefs.pausedReason().isNullOrBlank()) {
            prefs.clearPause()
        }

        // Cloud queue polling runs automatically while enabled, independent of sending execution.
        if (app.inventoryProvider.canReadSims() && Telephony.permitted(this)) {
            try {
                val snapshot = app.inventoryProvider.snapshot()
                val inventory = app.inventoryRepository.report(snapshot, allowRecent = execution.ready == 0 && execution.submitting == 0 && execution.submitted == 0 && execution.prepared == 0 && execution.unknown == 0)
                val ready = inventory as? GatewayInventoryState.Ready
                if (ready != null) {
                    val binding = ready.binding
                    val exactLocalSim = snapshot.sims.firstOrNull { it.subscriptionId == binding.subscriptionId && it.slotIndex == binding.slotIndex && !it.simIdentityHash.isNullOrBlank() }
                    if (exactLocalSim != null) {
                        val queueResult = app.cloudQueueRepository.sync(CloudQueueExecutionIdentity(deviceId, binding.simId, binding.subscriptionId, binding.slotIndex, exactLocalSim.simIdentityHash!!))
                        when (queueResult) {
                            is CloudQueueState.CredentialRejected -> { updateStatus("Gateway credential rejected. Re-pair this phone."); return }
                            is CloudQueueState.Offline -> updateStatus("Waiting for network. Durable local jobs are retained.")
                            is CloudQueueState.SimBlocked -> updateStatus("Blocked: ${queueResult.message}")
                            is CloudQueueState.Error -> updateStatus("Waiting: ${queueResult.message}")
                            else -> Unit
                        }
                    } else {
                        updateStatus("Blocked: exact Web-selected SIM identity is unavailable locally. No fallback SIM will be used.")
                    }
                } else {
                    when (inventory) {
                        GatewayInventoryState.Offline -> updateStatus("Waiting for network. Durable local jobs are retained.")
                        GatewayInventoryState.CredentialRejected -> { app.cloudQueueStore.recordSync(deviceId, System.currentTimeMillis(), "Gateway credential rejected"); updateStatus("Gateway credential rejected. Re-pair this phone."); return }
                        GatewayInventoryState.PermissionRequired -> updateStatus("Waiting for phone/SMS permission.")
                        is GatewayInventoryState.Missing -> updateStatus("Blocked: exact Web-selected SIM is missing. No fallback SIM will be used.")
                        is GatewayInventoryState.Unbound -> updateStatus("Waiting: select the sending SIM in BulkText Web.")
                        else -> updateStatus("Waiting for exact Web-selected SIM readiness.")
                    }
                }
            } catch (e: Exception) {
                updateStatus("Waiting: cloud queue sync error (${e.javaClass.simpleName}). Retrying automatically.")
            }
        } else {
            updateStatus("Waiting: SMS/phone permission is required before background queue sync.")
        }

        if (prefs.operationError(deviceId, "callbacks") != null || prefs.operationError(deviceId, "recovery") != null) {
            updateStatus("Cloud sync needs attention. Pending events retained; new SMS submission waiting.")
            return
        }

        val finalExecution = app.cloudQueueStore.executionCounts(deviceId)
        if (finalExecution.unknown > 0) {
            val reason = "Gateway recovery mode: an SMS is UNKNOWN. Queue/callback/recovery sync is active; sending remains paused."
            prefs.pause(reason)
            showNotification(reason)
            return
        }
        if (!prefs.pausedReason().isNullOrBlank()) prefs.clearPause()
        if (!BackgroundGatewayPolicy.canExecute(prefs.isEnabled(), prefs.pausedReason())) return
        if (finalExecution.submitting > 0) { updateStatus("Gateway active • campaign submission in progress"); return }

        val activatedAt = prefs.activatedAt()
        val next = app.cloudQueueStore.nextReadySince(deviceId, activatedAt)
        if (next == null) { updateStatus("Gateway active • waiting for Web-authorized campaigns"); return }

        // Preserve the recovery check at the execution boundary, not on empty cycles.
        try { app.cloudDeliveryRepository.syncRecoveryRequests(force = true) } catch (_: Exception) {
            updateStatus("Waiting for recovery confirmation before execution."); return
        }
        if (app.cloudQueueStore.executionCounts(deviceId).unknown > 0) return
        if (stopping || !prefs.submissionGate.isAllowed(permit)) return
        updateStatus("Gateway active • preparing campaign ${next.campaignId.take(8)}…")
        try {
            startForegroundService(Intent(this, CloudSendingService::class.java)
                .putExtra(CloudSendingService.EXTRA_STOP_GENERATION, permit.generation)
                .putExtra(CloudSendingService.EXTRA_AUTOMATIC, true)
                .putExtra(CloudSendingService.EXTRA_DEVICE_ID, deviceId)
                .putExtra(CloudSendingService.EXTRA_CAMPAIGN_ID, next.campaignId)
                .putExtra(CloudSendingService.EXTRA_NOT_BEFORE, activatedAt))
        } catch (e: Exception) {
            updateStatus("Gateway active • campaign send start deferred (${e.javaClass.simpleName})")
        }
    }

    private fun updateStatus(message: String) {
        if (stopping || !app.backgroundGatewayPreferences.isEnabled()) return
        app.backgroundGatewayPreferences.setStatus(message)
        showNotification(message)
    }

    private fun showNotification(message: String) {
        if (stopping || !app.backgroundGatewayPreferences.isEnabled()) return
        val nm = requireNotNull(getSystemService(NotificationManager::class.java))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "BulkText background gateway", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 180, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("BulkText Gateway active")
            .setContentText(message.take(120)).setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(NOTIFICATION_ID, notification)
    }

    companion object { private const val CHANNEL_ID = "background_gateway_0180"; private const val NOTIFICATION_ID = 180 }
}
