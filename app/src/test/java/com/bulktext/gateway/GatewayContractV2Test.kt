package com.bulktext.gateway

import com.bulktext.gateway.cloud.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GatewayContractV2Test {
    private val api = SupabaseGatewayApi("https://example.supabase.co", "fixture")
    private val client = "22222222-2222-4222-8222-222222222222"
    private fun row() = JSONObject("""{"attempt_id":"11111111-1111-4111-8111-111111111111","client_attempt_id":"$client","attempt_number":1,"state":"prepared","safe_retry_eligible":false,"created_at":"2026-10-06T00:00:00Z","sent_parts":0,"failed_sent_parts":0,"delivered_parts":0}""")
    private fun event() = GatewayAttemptEvent("33333333-3333-4333-8333-333333333333",client,"delivery",0,-1,1000,"3gpp2",2 shl 24,"a".repeat(64))
    @Test fun beginGrantsRequirePreparedIdentity() {
        val data=row().put("outcome","registered").put("can_submit",true)
        assertTrue(api.parseBeginV2(data,client).canSubmit)
        assertThrows(CloudException.ServerContractException::class.java) {api.parseBeginV2(data,"another")}
        data.put("state","submitted")
        assertThrows(CloudException.ServerContractException::class.java) {api.parseBeginV2(data,client)}
        data.put("can_submit",false);assertFalse(api.parseBeginV2(data,client).canSubmit)
    }
    @Test fun blockedBeginIsExplicitAndNeverGrants() {
        val data=JSONObject("""{"outcome":"blocked","can_submit":false,"code":"sim_gate"}""")
        assertEquals("sim_gate",api.parseBeginV2(data,client).blockCode)
        data.put("can_submit",true)
        assertThrows(CloudException.ServerContractException::class.java) {api.parseBeginV2(data,client)}
    }
    @Test fun eventPayloadPreservesRawEvidenceAndObservationIdentity() {
        val e=event();val p=api.buildEventPayload("owner","fixture",e)
        assertEquals(e.eventId,p.getString("p_event_id"));assertEquals(1000L,p.getLong("p_occurred_at_ms"))
        assertEquals(2 shl 24,p.getInt("p_delivery_status"));assertEquals(e.deliveryPduSha256,p.getString("p_delivery_pdu_sha256"))
        val absent=api.buildEventPayload("owner","fixture",e.copy(deliveryFormat=null,deliveryStatus=null,deliveryPduSha256=null))
        assertTrue(absent.isNull("p_delivery_status"));assertFalse(p.has("p_pdu"))
    }
    @Test fun terminalDispositionsDoNotInventTransportTruth() {
        assertEquals("tombstoned",api.parseReportV2(JSONObject("""{"outcome":"tombstoned"}"""),event()).outcome)
        assertEquals("rejected",api.parseReportV2(JSONObject("""{"outcome":"rejected","reason":"attempt_unavailable"}"""),event()).outcome)
        assertThrows(CloudException.ServerContractException::class.java) {api.parseReportV2(JSONObject("""{"outcome":"rejected","reason":"bad_payload"}"""),event())}
    }
    @Test fun conflictAndResolutionAreIndependentAndIdentityChecked() {
        val data=row().put("outcome","conflict").put("resolved_at","2026-10-10T00:00:00Z")
        val report=api.parseReportV2(data,event());assertTrue(report.conflict);assertTrue(report.resolved)
        data.put("client_attempt_id","44444444-4444-4444-8444-444444444444")
        assertThrows(CloudException.ServerContractException::class.java) {api.parseReportV2(data,event())}
    }
    @Test fun recoveryAckMustBeTerminal() {
        for (outcome in listOf("applied","superseded","rejected")) assertTrue(api.parseRecoveryAckV2(JSONObject().put("outcome",outcome).put("terminal",true)))
        assertThrows(CloudException.ServerContractException::class.java) {api.parseRecoveryAckV2(JSONObject("""{"outcome":"applied","terminal":false}"""))}
        assertThrows(CloudException.ServerContractException::class.java) {api.parseRecoveryAckV2(JSONObject("""{"outcome":"pending","terminal":true}"""))}
    }
}
