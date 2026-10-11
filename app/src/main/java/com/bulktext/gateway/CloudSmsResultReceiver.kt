package com.bulktext.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 0.18.4 cloud callback boundary.
 *
 * The Android callback is durably recorded locally before any cloud report is
 * attempted. Raw resultCode and the optional platform errorCode are retained
 * for device/carrier diagnostics. This receiver never sends or retries an SMS.
 */
class CloudSmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // goAsync() detaches the receiver's PendingResult. Read this first.
        val callbackResult = resultCode
        val attemptId = intent.getStringExtra(EXTRA_CLIENT_ATTEMPT_ID)
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        val kind = intent.getStringExtra(EXTRA_KIND)
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, -1)
        val errorCode = if (intent.hasExtra(PLATFORM_ERROR_CODE_EXTRA)) {
            intent.getIntExtra(PLATFORM_ERROR_CODE_EXTRA, 0)
        } else null
        if (attemptId.isNullOrBlank() || eventId.isNullOrBlank() ||
            (kind != KIND_SENT && kind != KIND_DELIVERY) || partIndex < 0) return
        val expectedAction = "com.bulktext.gateway.CLOUD_SMS_${kind.uppercase()}_${attemptId}_${partIndex}"
        if (intent.action != expectedAction) return
        val receivedAt = System.currentTimeMillis()
        val evidence = if (kind == KIND_DELIVERY) CloudCallbackPolicy.parseEvidence(intent.getByteArrayExtra("pdu"), intent.getStringExtra("format")) else null
        val pending = goAsync()
        Thread({
            try {
                if (!attemptId.isNullOrBlank() && !eventId.isNullOrBlank() &&
                    (kind == KIND_SENT || kind == KIND_DELIVERY) && partIndex >= 0
                ) {
                    val app = context.applicationContext as GatewayApp
                    app.cloudQueueStore.recordAttemptCallback(
                        clientAttemptId = attemptId,
                        eventId = java.util.UUID.randomUUID().toString(),
                        partIndex = partIndex,
                        kind = kind,
                        resultCode = callbackResult,
                        errorCode = errorCode,
                        atMillis = receivedAt,
                        deliveryEvidence = evidence
                    )
                    CallbackSyncService.schedule(context.applicationContext)
                }
            } catch (_: Exception) {
                android.util.Log.e("BulkTextCallbacks", "Callback persistence failed; no success inferred")
            } finally {
                pending.finish()
            }
        }, "BulkTextCloudCallback0184").start()
    }

    companion object {
        const val EXTRA_CLIENT_ATTEMPT_ID = "cloud_client_attempt_id"
        const val EXTRA_EVENT_ID = "cloud_event_id"
        const val EXTRA_PART_INDEX = "cloud_part_index"
        const val EXTRA_KIND = "cloud_callback_kind"
        const val KIND_SENT = "sent"
        const val KIND_DELIVERY = "delivery"

        // Android SmsManager documents this optional callback extra as "errorCode".
        // Use the literal key so source remains compatible across supported API levels.
        const val PLATFORM_ERROR_CODE_EXTRA = "errorCode"
    }
}
