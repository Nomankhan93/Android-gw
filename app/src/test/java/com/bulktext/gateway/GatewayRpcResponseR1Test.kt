package com.bulktext.gateway

import com.bulktext.gateway.cloud.*
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Exercises public RPC methods through HTTP transport and raw response decoding. */
class GatewayRpcResponseR1Test {
    private val client="22222222-2222-4222-8222-222222222222"
    private val row="""{"outcome":"registered","can_submit":true,"attempt_id":"11111111-1111-4111-8111-111111111111","client_attempt_id":"$client","attempt_number":1,"state":"prepared","created_at":"2026-10-06T00:00:00Z","safe_retry_eligible":false}"""
    private class Response(url:URL,val body:String):HttpURLConnection(url) {
        val request=ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy()=false
        override fun getOutputStream()=request
        override fun getResponseCode()=200
        override fun getInputStream()=ByteArrayInputStream(body.toByteArray())
    }
    private fun api(body:String, observe:(Response)->Unit={}):SupabaseGatewayApi = SupabaseGatewayApi("https://example.invalid","fixture") { url -> Response(url,body).also(observe) }
    @Test fun beginConsumesScalarObjectThroughPublicRpc() {
        var connection:Response?=null
        val result=api(row) {connection=it}.beginGatewayMessageAttempt("owner","credential","job",client,1)
        assertTrue(result.canSubmit);assertEquals(client,result.clientAttemptId)
        assertTrue(connection!!.url.path.endsWith("/begin_gateway_message_attempt_v2"))
        assertTrue(connection!!.request.toString().contains(client))
    }
    @Test fun callbackConsumesScalarDispositionAndPreservesEventUuid() {
        val event=GatewayAttemptEvent("event",client,"submitted",null,null,1000)
        var connection:Response?=null
        assertEquals("tombstoned",api("""{"outcome":"tombstoned"}""") {connection=it}.reportGatewayMessageAttemptEvent("owner","credential",event).outcome)
        assertTrue(connection!!.url.path.endsWith("/report_gateway_message_attempt_event_v2"))
        assertTrue(connection!!.request.toString().contains("p_event_id"))
    }
    @Test fun recoveryConsumesTerminalObjectThroughPublicRpc() {
        assertTrue(api("""{"outcome":"applied","terminal":true}""").acknowledgeGatewayMessageRecovery("owner","credential","recovery","applied"))
    }
    @Test fun v2WrongShapeAndIncompleteObjectsFailClosed() {
        for (body in listOf("[$row]","[]","null","true","{}",row+" trailing")) {
            assertThrows(Exception::class.java) {api(body).beginGatewayMessageAttempt("owner","credential","job",client,1)}
        }
    }
    @Test fun legacyTableResponseStillRequiresArray() {
        val a=api(row)
        assertThrows(CloudException.ServerContractException::class.java) {a.parseSingleRowResult(row)}
        assertEquals("registered",a.parseSingleRowResult("[$row]").getString("outcome"))
    }
    @Test fun pendingRecoveryResponseNeverAcknowledges() {
        assertThrows(CloudException.ServerContractException::class.java) {api("""{"outcome":"pending","terminal":false}""").acknowledgeGatewayMessageRecovery("owner","credential","recovery","applied")}
    }
}
