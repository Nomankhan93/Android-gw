package com.bulktext.gateway.queue

import com.bulktext.gateway.cloud.GatewayQueueJob
import com.bulktext.gateway.cloud.QueueLeaseReceipt

interface CloudQueueLocalStore {
    fun persist(deviceId: String, job: GatewayQueueJob): PersistResult
    fun pendingAckReceipts(deviceId: String, limit: Int = 25): List<QueueLeaseReceipt>
    fun markAcknowledged(deviceId: String, receipts: List<QueueLeaseReceipt>): Int
    fun counts(deviceId: String): CloudQueueCounts
    fun recordSync(deviceId: String, atMillis: Long, error: String? = null)
    fun exportDiagnostics(deviceId: String?): String
}

enum class PersistResult {
    INSERTED,
    SAME_LEASE,
    NEWER_LEASE
}
