package com.bulktext.gateway.cloud

interface GatewayDeliveryApi {
    @Throws(Exception::class)
    fun beginGatewayMessageAttempt(
        deviceId: String,
        credential: String,
        jobId: String,
        clientAttemptId: String,
        expectedParts: Int
    ): GatewayAttemptStart

    @Throws(Exception::class)
    fun reportGatewayMessageAttemptEvent(
        deviceId: String,
        credential: String,
        event: GatewayAttemptEvent
    ): GatewayAttemptReport

    @Throws(Exception::class)
    fun listGatewayMessageRecoveryRequests(
        deviceId: String,
        credential: String,
        limit: Int = 25
    ): List<GatewayRecoveryRequest>

    @Throws(Exception::class)
    fun acknowledgeGatewayMessageRecovery(
        deviceId: String,
        credential: String,
        recoveryId: String,
        outcome: String = "applied"
    ): Boolean
}
