package com.bulktext.gateway

/** Raw Android status, never a shifted CDMA value. No status authorizes resend. */
data class DeliveryEvidence(val format: String, val status: Int, val sha256: String) {
    fun state(): String = when (format) {
        "3gpp" -> when (status) { in 0..31 -> "DELIVERED"; in 64..255 -> "FAILED"; else -> "PENDING" }
        "3gpp2" -> if (status < 0 || status > 67043328 || status and 65535 != 0) "PENDING"
            else if (status == 0) "DELIVERED" else if ((status ushr 24) and 3 >= 2) "FAILED" else "PENDING"
        else -> "PENDING"
    }
}
