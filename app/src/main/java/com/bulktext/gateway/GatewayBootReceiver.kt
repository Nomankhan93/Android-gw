package com.bulktext.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 0.18.6 Boot & Package Replacement Receiver.
 *
 * Restores gateway control-plane capability after device reboot or package upgrade
 * IF AND ONLY IF the user previously enabled the gateway.
 *
 * Safety Invariant: This receiver NEVER submits SMS, NEVER calls SmsManager, and NEVER
 * auto-enables the gateway if previously disabled by the operator.
 */
class GatewayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val app = context.applicationContext as? GatewayApp ?: return
        val prefs = app.backgroundGatewayPreferences

        // Persisted user enablement state is authoritative. Reboot/upgrade never auto-enables.
        if (!prefs.isEnabled()) return

        // 1. Schedule control-plane background sync / outbox flushes.
        if (app.secureCredentialStore.getDeviceId()?.let { app.cloudQueueStore.pendingAttemptEvents(it, 1).isNotEmpty() } == true) {
            CallbackSyncService.schedule(context)
        }

        // 2. Schedule watchdog for periodic control-plane health checks.
        GatewayWatchdogService.schedule(context)

        // 3. Re-establish foreground gateway service if allowed by Android background start rules.
        try {
            val serviceIntent = Intent(context, BackgroundGatewayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // Android 12+ / OEM background start restrictions may block startForegroundService from boot.
            // Catch safely without crashing; status instructs operator to open app if needed.
            prefs.setStatus("Gateway enabled; open app to re-establish foreground worker.")
        }
    }
}
