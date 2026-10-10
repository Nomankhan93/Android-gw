package com.bulktext.gateway.cloud

interface GatewayQueueApi {
    @Throws(Exception::class)
    fun claimGatewayMessageJobs(
        deviceId: String,
        credential: String,
        limit: Int = 20
    ): List<GatewayQueueJob>

    @Throws(Exception::class)
    fun acknowledgeGatewayMessageJobs(
        deviceId: String,
        credential: String,
        receipts: List<QueueLeaseReceipt>
    ): Int

    @Throws(Exception::class)
    fun releaseGatewayMessageJobLeases(
        deviceId: String,
        credential: String,
        receipts: List<QueueLeaseReceipt>
    ): Int
}
