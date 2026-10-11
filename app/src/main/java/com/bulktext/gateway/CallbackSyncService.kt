package com.bulktext.gateway

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** Network-only outbox worker. It cannot claim, recover, or submit SMS jobs. */
class CallbackSyncService : JobService() {
    @Volatile private var cancelled: AtomicBoolean? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val stop = AtomicBoolean(false)
        cancelled = stop
        Thread({
            var retry = true
            try {
                val app = application as GatewayApp
                if (!stop.get()) {
                    // Bound each run; JobScheduler retries durable remaining events.
                    val synced = app.cloudDeliveryRepository.flushEvents(10)
                    retry = app.secureCredentialStore.getDeviceId()?.let { app.cloudQueueStore.pendingAttemptEvents(it, 1).isNotEmpty() } ?: false
                    if (synced > 0 || !retry) app.backgroundGatewayPreferences.markCallbackFlush()
                }
            } catch (_: Exception) {
                // Preserve the outbox. Do not put credentials or recipient data in logs.
            } finally {
                if (!stop.get()) jobFinished(params, retry)
            }
        }, "BulkTextCallbackSync0184").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cancelled?.set(true)
        return true
    }

    companion object {
        private const val JOB_ID = 184

        fun schedule(context: Context) {
            try {
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, CallbackSyncService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(1_000L)
                    .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true)
                    .build()
                val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
                val result = scheduler.schedule(job)
                if (result != JobScheduler.RESULT_SUCCESS) Log.w("BulkTextCallbacks", "Callback sync scheduling deferred")
            } catch (_: Exception) {
                Log.w("BulkTextCallbacks", "Callback sync scheduling deferred")
            }
        }
    }
}
