package com.bulktext.gateway

import android.app.PendingIntent
import android.os.Build
import android.telephony.SmsMessage

object CloudCallbackPolicy {
    // Explicit receiver component + unique action keep correlation fixed; the platform
    // must be able to add errorCode/pdu/format. API 26–30 are mutable by default.
    fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    fun parseEvidence(pdu: ByteArray?, format: String?): DeliveryEvidence? {
        if (pdu == null || format !in setOf("3gpp", "3gpp2")) return null
        return try {
            val report = SmsMessage.createFromPdu(pdu, format) ?: return null
            if (!report.isStatusReportMessage) return null
            DeliveryEvidence(format!!, report.status, java.security.MessageDigest.getInstance("SHA-256")
                .digest(pdu).joinToString("") { "%02x".format(it.toInt() and 255) })
        } catch (_: Exception) { null }
    }

    fun isConfirmedDelivery(resultCode: Int, pdu: ByteArray?, format: String?): Boolean {
        if (resultCode != android.app.Activity.RESULT_OK || pdu == null ||
            format !in setOf("3gpp", "3gpp2")) return false
        return try {
            val report = SmsMessage.createFromPdu(pdu, format) ?: return false
            report.isStatusReportMessage && isSuccessfulStatus(format!!, report.status)
        } catch (_: Exception) { false }
    }

    // Conservative hotfix: only explicit successful status is promoted. Pending,
    // failure, malformed and absent reports remain diagnostics, never resend grants.
    fun isSuccessfulStatus(format: String, status: Int): Boolean = when (format) {
        "3gpp" -> status in 0..31
        "3gpp2" -> status == 0
        else -> false
    }
}
