package com.bulktext.gateway

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** Secondary, best-effort control-plane worker. Sending stays behind CloudSendingService gates. */
class GatewayWatchdogService : JobService() {
    private data class Run(val params: JobParameters, val cancelled: AtomicBoolean = AtomicBoolean(false))
    private var current: Run? = null // JobService lifecycle callbacks and completion run on main.
    private val main = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        current?.cancelled?.set(true)
        val run = Run(params)
        current = run
        Thread({
            try {
                val app = application as GatewayApp
                val prefs = app.backgroundGatewayPreferences
                val permit = prefs.submissionGate.capture(automatic = true) ?: return@Thread
                val deviceId = app.secureCredentialStore.getDeviceId()
                GatewayWatchdogPass(
                    allowed = { !run.cancelled.get() && prefs.submissionGate.isAllowed(permit) && app.secureCredentialStore.getDeviceId() == deviceId },
                    flushCallbacks = { if (!deviceId.isNullOrBlank()) app.cloudDeliveryRepository.flushEvents(10) },
                    syncRecovery = { if (!deviceId.isNullOrBlank()) app.cloudDeliveryRepository.syncRecoveryRequests() },
                    needsRestart = { GatewayWatchdogPolicy.needsRestart(prefs.isEnabled(), prefs.activatedAt(), prefs.lastControlCycleAt(), System.currentTimeMillis()) },
                    restart = {
                        prefs.submissionGate.runIfAllowed(permit) {
                            if (!run.cancelled.get()) {
                                try {
                                    applicationContext.startForegroundService(Intent(applicationContext, BackgroundGatewayService::class.java))
                                } catch (_: Exception) {
                                    prefs.setStatus("Gateway needs attention: open app to resume background work.")
                                }
                            }
                        }
                    }
                ).run()
            } catch (e: Exception) {
                Log.w("BulkTextWatchdog", "Watchdog pass interrupted: ${e.javaClass.simpleName}")
            } finally {
                main.post {
                    if (current === run && !run.cancelled.get()) {
                        current = null
                        jobFinished(params, false)
                    }
                }
            }
        }, "BulkTextWatchdog0186r1").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        current?.takeIf { it.params.jobId == params.jobId }?.let { it.cancelled.set(true); current = null }
        return (application as GatewayApp).backgroundGatewayPreferences.isEnabled()
    }

    override fun onDestroy() {
        current?.cancelled?.set(true)
        current = null
        super.onDestroy()
    }

    companion object {
        const val JOB_ID = 186

        /** Keep matching persisted/running jobs intact; scheduling the same ID can stop a run. */
        fun schedule(context: Context): Boolean {
            try {
                if (!BackgroundGatewayPreferences(context).isEnabled()) return false
                val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
                val component = ComponentName(context, GatewayWatchdogService::class.java)
                val existing = scheduler.getPendingJob(JOB_ID)
                if (existing != null && existing.service == component && existing.isPeriodic &&
                    existing.intervalMillis == GatewayWatchdogPolicy.INTERVAL_MS && existing.isPersisted) return true
                val job = JobInfo.Builder(JOB_ID, component)
                    .setPeriodic(GatewayWatchdogPolicy.INTERVAL_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
                if (scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) return true
            } catch (_: Exception) { /* Scheduling is best effort, never a reason to enable sending. */ }
            BackgroundGatewayPreferences(context).setStatus("Gateway needs attention: watchdog unavailable; open app to check background work.")
            Log.w("BulkTextWatchdog", "Watchdog scheduling deferred")
            return false
        }

        fun cancel(context: Context) {
            try { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
            catch (_: Exception) { Log.w("BulkTextWatchdog", "Watchdog cancellation deferred; disabled state still blocks work") }
        }
    }
}
