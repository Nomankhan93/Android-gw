package com.bulktext.gateway

import org.junit.Assert.*
import org.junit.Test

class CloudDeliveryEvidenceTest {
    @Test fun gsmPendingAndFailedStatusAreNotDelivery() {
        assertTrue(CloudCallbackPolicy.isSuccessfulStatus("3gpp", 0))
        assertTrue(CloudCallbackPolicy.isSuccessfulStatus("3gpp", 31))
        for (status in listOf(-1, 32, 63, 64, 127, 255)) {
            assertFalse(CloudCallbackPolicy.isSuccessfulStatus("3gpp", status))
        }
    }

    @Test fun cdmaUnknownOrFailureStatusIsConservative() {
        assertTrue(CloudCallbackPolicy.isSuccessfulStatus("3gpp2", 0))
        for (status in listOf(-1, 1, 0x02000000, 0x03000000)) {
            assertFalse(CloudCallbackPolicy.isSuccessfulStatus("3gpp2", status))
        }
        assertFalse(CloudCallbackPolicy.isSuccessfulStatus("unknown", 0))
    }

    @Test fun resultOkWithoutReportIsNotProof() {
        assertFalse(CloudCallbackPolicy.isConfirmedDelivery(-1, null, "3gpp"))
        assertFalse(CloudCallbackPolicy.isConfirmedDelivery(-1, byteArrayOf(), null))
        assertFalse(CloudCallbackPolicy.isConfirmedDelivery(-1, byteArrayOf(), "unsupported"))
        assertFalse(CloudCallbackPolicy.isConfirmedDelivery(1, byteArrayOf(), "3gpp"))
    }
}
