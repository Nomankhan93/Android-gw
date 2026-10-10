package com.bulktext.gateway

import com.bulktext.gateway.cloud.GatewayAttemptEvent
import com.bulktext.gateway.cloud.GatewayRecoveryRequest
import com.bulktext.gateway.cloud.SupabaseGatewayApi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GatewayDeliveryTest {
    @Test fun parsesAttemptStartContract() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")
        val parsed = api.parseAttemptStart(JSONObject("""{
          "attempt_id":"11111111-1111-4111-8111-111111111111",
          "client_attempt_id":"22222222-2222-4222-8222-222222222222",
          "attempt_number":2,
          "state":"prepared",
          "safe_retry_eligible":false,
          "created_at":"2026-10-06T00:00:00Z"
        }"""))
        assertEquals(2, parsed.attemptNumber)
        assertEquals("prepared", parsed.state)
        assertFalse(parsed.safeRetryEligible)
    }

    @Test fun parsesAttemptReportContract() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")
        val parsed = api.parseAttemptReport(JSONObject("""{
          "attempt_id":"11111111-1111-4111-8111-111111111111",
          "client_attempt_id":"22222222-2222-4222-8222-222222222222",
          "attempt_number":1,
          "state":"sent",
          "safe_retry_eligible":false,
          "sent_parts":3,
          "failed_sent_parts":0,
          "delivered_parts":1
        }"""))
        assertEquals("sent", parsed.state)
        assertEquals(3, parsed.sentParts)
        assertEquals(1, parsed.deliveredParts)
    }

    @Test fun recoveryRowsRequireExplicitActionAndAttemptIdentity() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")
        val rows = api.parseRecoveryRows("""[{
          "recovery_id":"11111111-1111-4111-8111-111111111111",
          "action":"safe_retry",
          "job_id":"22222222-2222-4222-8222-222222222222",
          "campaign_id":"33333333-3333-4333-8333-333333333333",
          "failed_client_attempt_id":"44444444-4444-4444-8444-444444444444",
          "attempt_number":1,
          "requested_at":"2026-10-06T00:00:00Z"
        }]""")
        assertEquals(1, rows.size)
        assertEquals("safe_retry", rows.single().action)
        assertEquals(1, rows.single().attemptNumber)
    }

    @Test fun callbackEventRequiresPartForSentAndDelivery() {
        assertThrows(IllegalArgumentException::class.java) {
            GatewayAttemptEvent(
                eventId = "11111111-1111-4111-8111-111111111111",
                clientAttemptId = "22222222-2222-4222-8222-222222222222",
                eventType = "sent",
                partIndex = null,
                resultCode = null,
                occurredAtMillis = 1L
            )
        }
    }

    @Test fun recoveryRequestValidationAndSemantics() {
        val req = GatewayRecoveryRequest(
            recoveryId = "11111111-1111-4111-8111-111111111111",
            action = "skip_unknown",
            jobId = "22222222-2222-4222-8222-222222222222",
            campaignId = "33333333-3333-4333-8333-333333333333",
            failedClientAttemptId = "44444444-4444-4444-8444-444444444444",
            attemptNumber = 1,
            requestedAt = 1000L
        )
        assertEquals("skip_unknown", req.action)
        assertEquals(1, req.attemptNumber)

        assertThrows(IllegalArgumentException::class.java) {
            GatewayRecoveryRequest(
                recoveryId = "11111111-1111-4111-8111-111111111111",
                action = "invalid_action",
                jobId = "22222222-2222-4222-8222-222222222222",
                campaignId = "33333333-3333-4333-8333-333333333333",
                failedClientAttemptId = "44444444-4444-4444-8444-444444444444",
                attemptNumber = 1,
                requestedAt = 1000L
            )
        }
    }

    @Test fun callbackDiagnosticsRetainPlatformErrorCodeKey() {
        assertEquals("errorCode", CloudSmsResultReceiver.PLATFORM_ERROR_CODE_EXTRA)
    }

    @Test fun callbackEventValidationAndDiagnosticsSemantics() {
        val event = GatewayAttemptEvent(
            eventId = "11111111-1111-4111-8111-111111111111",
            clientAttemptId = "22222222-2222-4222-8222-222222222222",
            eventType = "sent",
            partIndex = 0,
            resultCode = -1,
            occurredAtMillis = 1000L
        )
        assertEquals("sent", event.eventType)
        assertEquals(-1, event.resultCode)
        assertEquals(0, event.partIndex)
    }
}
