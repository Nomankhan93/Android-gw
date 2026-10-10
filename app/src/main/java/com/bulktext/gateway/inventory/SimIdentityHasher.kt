package com.bulktext.gateway.inventory

import java.security.MessageDigest

/**
 * Defensive SIM fingerprint made only from non-secret Android subscription metadata.
 * It intentionally does not collect phone number, ICCID, IMSI, IMEI, or serial number.
 * It is not treated as a globally stable hardware identifier.
 */
object SimIdentityHasher {
    fun hash(
        subscriptionId: Int,
        slotIndex: Int,
        carrierId: Int,
        carrierName: String?,
        countryIso: String?,
        isEmbedded: Boolean
    ): String {
        val material = listOf(
            subscriptionId.toString(),
            slotIndex.toString(),
            carrierId.toString(),
            carrierName.orEmpty().trim(),
            countryIso.orEmpty().trim().lowercase(),
            if (isEmbedded) "1" else "0"
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
