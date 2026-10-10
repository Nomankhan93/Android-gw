package com.bulktext.gateway.cloud

data class GatewayQueueJob(
    val jobId: String,
    val dispatchId: String,
    val campaignId: String,
    val sourceRowNumber: Int,
    val normalizedE164: String,
    val renderedMessage: String,
    val smsEncoding: String,
    val segmentCount: Int,
    val leaseToken: String,
    val leaseVersion: Int,
    val leasedAt: Long,
    val leaseExpiresAt: Long,
    val gatewaySimId: String,
    val simSubscriptionId: Int,
    val simSlotIndex: Int,
    val simIdentityHash: String
) {
    init {
        require(sourceRowNumber >= 2) { "Queue source row number is invalid" }
        require(normalizedE164.matches(Regex("^\\+923[0-9]{9}$"))) { "Queue phone number is invalid" }
        require(renderedMessage.isNotEmpty() && renderedMessage.length <= 16000) { "Queue message size is invalid" }
        require(smsEncoding == "GSM-7" || smsEncoding == "Unicode") { "Queue SMS encoding is invalid" }
        require(segmentCount > 0) { "Queue segment count is invalid" }
        require(leaseVersion > 0) { "Queue lease version is invalid" }
        require(leaseExpiresAt > leasedAt) { "Queue lease expiry is invalid" }
        require(simSubscriptionId >= 0) { "Queue subscription id is invalid" }
        require(simSlotIndex in 0..7) { "Queue SIM slot is invalid" }
        require(simIdentityHash.matches(Regex("^[0-9a-f]{64}$"))) { "Queue SIM identity hash is invalid" }
    }

    fun receipt(): QueueLeaseReceipt = QueueLeaseReceipt(jobId, leaseToken, leaseVersion)
}

data class QueueLeaseReceipt(
    val jobId: String,
    val leaseToken: String,
    val leaseVersion: Int
) {
    init {
        require(jobId.isNotBlank()) { "Queue job id is required" }
        require(leaseToken.isNotBlank()) { "Queue lease token is required" }
        require(leaseVersion > 0) { "Queue lease version is invalid" }
    }
}
