package com.bulktext.gateway

import com.bulktext.gateway.cloud.*
import com.bulktext.gateway.identity.CredentialStore
import com.bulktext.gateway.queue.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GatewayQueueTest {
    private val now = 1_800_000_000_000L
    private val deviceId = "11111111-1111-4111-8111-111111111111"
    private val simId = "22222222-2222-4222-8222-222222222222"
    private val identity = CloudQueueExecutionIdentity(deviceId, simId, 11, 0, "a".repeat(64))

    @Test
    fun queueParserUsesExact016Contract() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")
        val parsed = api.parseQueueJobRows("[${queueJson(leaseVersion = 1)}]")
        assertEquals(1, parsed.size)
        val job = parsed.single()
        assertEquals("33333333-3333-4333-8333-333333333333", job.jobId)
        assertEquals(11, job.simSubscriptionId)
        assertEquals(0, job.simSlotIndex)
        assertEquals("a".repeat(64), job.simIdentityHash)
        assertTrue(job.leaseExpiresAt > job.leasedAt)
    }

    @Test
    fun malformedQueuePayloadIsRejected() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")
        val bad = JSONObject(queueJson()).apply { put("sim_identity_hash", "bad") }
        assertThrows(CloudException.ServerContractException::class.java) {
            api.parseQueueJob(bad)
        }
        assertThrows(CloudException.ServerContractException::class.java) {
            api.parseQueueJobRows("not-json")
        }
    }

    @Test
    fun ackPayloadUsesExact016ReceiptFields() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")
        val receipt = QueueLeaseReceipt(
            "33333333-3333-4333-8333-333333333333",
            "44444444-4444-4444-8444-444444444444",
            2
        )
        val payload = api.queueReceiptPayload(deviceId, "credential-12345678901234567890", listOf(receipt))
        assertEquals(setOf("p_device_id", "p_credential", "p_receipts"), payload.keys().asSequence().toSet())
        val row = payload.getJSONArray("p_receipts").getJSONObject(0)
        assertEquals(setOf("jobId", "leaseToken", "leaseVersion"), row.keys().asSequence().toSet())
    }

    @Test
    fun sameJobNewerLeaseReconcilesWithoutDuplicate() {
        val first = job(leaseVersion = 1, leaseToken = "44444444-4444-4444-8444-444444444444")
        val newer = first.copy(
            leaseToken = "55555555-5555-4555-8555-555555555555",
            leaseVersion = 2,
            leasedAt = first.leasedAt + 200_000,
            leaseExpiresAt = first.leaseExpiresAt + 200_000
        )
        val existing = CloudJobReconciler.StoredShape(first, acknowledged = false)
        assertEquals(PersistResult.NEWER_LEASE, CloudJobReconciler.decide(existing, newer))
    }

    @Test
    fun immutablePayloadChangeForStableJobIsIntegrityError() {
        val first = job()
        val changed = first.copy(renderedMessage = "changed", leaseVersion = 2, leaseToken = "55555555-5555-4555-8555-555555555555")
        assertThrows(CloudQueueIntegrityException::class.java) {
            CloudJobReconciler.decide(CloudJobReconciler.StoredShape(first, false), changed)
        }
    }

    @Test
    fun ackedJobRejectsSameOrNewerLease() {
        val first = job(leaseVersion = 1, leaseToken = "44444444-4444-4444-8444-444444444444")
        val ackedShape = CloudJobReconciler.StoredShape(first, acknowledged = true)

        assertThrows(CloudQueueIntegrityException::class.java) {
            CloudJobReconciler.decide(ackedShape, first)
        }

        val newer = first.copy(
            leaseToken = "55555555-5555-4555-8555-555555555555",
            leaseVersion = 2,
            leasedAt = first.leasedAt + 200_000,
            leaseExpiresAt = first.leaseExpiresAt + 200_000
        )
        assertThrows(CloudQueueIntegrityException::class.java) {
            CloudJobReconciler.decide(ackedShape, newer)
        }
    }

    @Test
    fun credentialErrorClassificationHardensQueueEndpoints() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")
        val endpoints = listOf("claim_gateway_message_jobs", "acknowledge_gateway_message_jobs", "release_gateway_message_job_leases")
        for (ep in endpoints) {
            val ex401 = api.classifyApiError(ep, 401, """{"code":"42501","message":"Authentication required"}""")
            assertTrue(ex401 is CloudException.CredentialRejectedException)

            val ex403 = api.classifyApiError(ep, 403, """{"code":"42501","message":"Permission required"}""")
            assertTrue(ex403 is CloudException.CredentialRejectedException)
        }
    }

    @Test
    fun repositoryPersistsBeforeAck() {
        val events = mutableListOf<String>()
        val store = FakeLocalStore(events)
        val api = FakeQueueApi(events).apply { claimed = listOf(job()) }
        val repo = CloudQueueRepository(FakeCredentialStore(deviceId), api, store) { now }

        val result = repo.sync(identity)
        assertTrue(result is CloudQueueState.Ready)
        assertEquals(listOf("claim", "persist", "ack", "markAck"), events)
        assertEquals(1, store.counts(deviceId).acknowledgedJobs)
    }

    @Test
    fun persistenceFailureDoesNotAckAndOnlyUnpersistedMayRelease() {
        val events = mutableListOf<String>()
        val store = FakeLocalStore(events).apply { failPersistence = true }
        val api = FakeQueueApi(events).apply { claimed = listOf(job()) }
        val repo = CloudQueueRepository(FakeCredentialStore(deviceId), api, store) { now }

        val result = repo.sync(identity)
        assertTrue(result is CloudQueueState.Error)
        assertFalse(events.contains("ack"))
        assertTrue(events.contains("release"))
    }

    @Test
    fun ackNetworkFailureRetainsDurablePendingJob() {
        val events = mutableListOf<String>()
        val store = FakeLocalStore(events)
        val api = FakeQueueApi(events).apply {
            claimed = listOf(job())
            ackNetworkFailure = true
        }
        val repo = CloudQueueRepository(FakeCredentialStore(deviceId), api, store) { now }

        val result = repo.sync(identity)
        assertTrue(result is CloudQueueState.Offline)
        assertEquals(1, store.counts(deviceId).persistedJobs)
        assertEquals(1, store.counts(deviceId).unacknowledgedJobs)
    }

    @Test
    fun staleAckCanReconcileToNewLeaseWithoutDuplicateLocalJob() {
        val events = mutableListOf<String>()
        val store = FakeLocalStore(events)
        val old = job(leaseVersion = 1, leaseToken = "44444444-4444-4444-8444-444444444444")
        store.persist(deviceId, old)
        events.clear()
        val newer = old.copy(
            leaseToken = "55555555-5555-4555-8555-555555555555",
            leaseVersion = 2,
            leasedAt = old.leasedAt + 200_000,
            leaseExpiresAt = old.leaseExpiresAt + 200_000
        )
        val api = FakeQueueApi(events).apply {
            staleFirstAck = true
            claimed = listOf(newer)
        }
        val repo = CloudQueueRepository(FakeCredentialStore(deviceId), api, store) { now }

        val result = repo.sync(identity)
        assertTrue(result is CloudQueueState.Ready)
        assertEquals(1, store.counts(deviceId).persistedJobs)
        assertEquals(0, store.counts(deviceId).unacknowledgedJobs)
        assertTrue(events.indexOf("claim") < events.lastIndexOf("ack"))
    }

    @Test
    fun exactSimMismatchBlocksBeforePersistenceOrAck() {
        val events = mutableListOf<String>()
        val store = FakeLocalStore(events)
        val api = FakeQueueApi(events).apply { claimed = listOf(job().copy(simSubscriptionId = 22, simSlotIndex = 1, simIdentityHash = "b".repeat(64))) }
        val repo = CloudQueueRepository(FakeCredentialStore(deviceId), api, store) { now }

        val result = repo.sync(identity)
        assertTrue(result is CloudQueueState.Error)
        assertFalse(events.contains("persist"))
        assertFalse(events.contains("ack"))
    }

    @Test
    fun queueErrorClassificationSeparatesLeaseAndSimBlocking() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")
        val stale = api.classifyApiError(
            "acknowledge_gateway_message_jobs",
            409,
            """{"code":"40001","message":"Stale or mismatched queue lease ACK"}"""
        )
        assertTrue(stale is CloudException.QueueLeaseConflictException)

        val missing = api.classifyApiError(
            "claim_gateway_message_jobs",
            400,
            """{"code":"22023","message":"Exact Web-selected SIM is missing; no SIM fallback is permitted"}"""
        )
        assertTrue(missing is CloudException.QueueBlockedException)
    }

    private fun job(
        leaseVersion: Int = 1,
        leaseToken: String = "44444444-4444-4444-8444-444444444444"
    ) = GatewayQueueJob(
        jobId = "33333333-3333-4333-8333-333333333333",
        dispatchId = "66666666-6666-4666-8666-666666666666",
        campaignId = "77777777-7777-4777-8777-777777777777",
        sourceRowNumber = 2,
        normalizedE164 = "+923001234567",
        renderedMessage = "BulkText queue test",
        smsEncoding = "GSM-7",
        segmentCount = 1,
        leaseToken = leaseToken,
        leaseVersion = leaseVersion,
        leasedAt = 1_790_000_000_000L,
        leaseExpiresAt = 1_790_000_120_000L,
        gatewaySimId = simId,
        simSubscriptionId = 11,
        simSlotIndex = 0,
        simIdentityHash = "a".repeat(64)
    )

    private fun queueJson(leaseVersion: Int = 1): String = """{
      "job_id":"33333333-3333-4333-8333-333333333333",
      "dispatch_id":"66666666-6666-4666-8666-666666666666",
      "campaign_id":"77777777-7777-4777-8777-777777777777",
      "source_row_number":2,
      "normalized_e164":"+923001234567",
      "rendered_message":"BulkText queue test",
      "sms_encoding":"GSM-7",
      "segment_count":1,
      "lease_token":"44444444-4444-4444-8444-444444444444",
      "lease_version":$leaseVersion,
      "leased_at":"2026-10-04T20:00:00Z",
      "lease_expires_at":"2026-10-04T20:02:00Z",
      "gateway_sim_id":"$simId",
      "sim_subscription_id":11,
      "sim_slot_index":0,
      "sim_identity_hash":"${"a".repeat(64)}"
    }""".trimIndent()

    private class FakeCredentialStore(private var deviceId: String?) : CredentialStore {
        private var credential: String? = "credential-12345678901234567890"
        override fun saveCredential(deviceId: String, credential: String, version: Int, expiresAt: Long, deviceName: String): Boolean { this.deviceId = deviceId; this.credential = credential; return true }
        override fun getDeviceId(): String? = deviceId
        override fun getDeviceName(): String = "Phone"
        override fun getCredential(): String? = credential
        override fun getCredentialVersion(): Int = 1
        override fun getExpiresAt(): Long = Long.MAX_VALUE
        override fun clear(): Boolean { deviceId = null; credential = null; return true }
    }

    private class FakeQueueApi(private val events: MutableList<String>) : GatewayQueueApi {
        var claimed: List<GatewayQueueJob> = emptyList()
        var ackNetworkFailure = false
        var staleFirstAck = false
        private var ackCalls = 0

        override fun claimGatewayMessageJobs(deviceId: String, credential: String, limit: Int): List<GatewayQueueJob> {
            events += "claim"
            return claimed
        }

        override fun acknowledgeGatewayMessageJobs(deviceId: String, credential: String, receipts: List<QueueLeaseReceipt>): Int {
            events += "ack"
            ackCalls++
            if (ackNetworkFailure) throw CloudException.NetworkException("offline")
            if (staleFirstAck && ackCalls == 1) throw CloudException.QueueLeaseConflictException()
            return receipts.size
        }

        override fun releaseGatewayMessageJobLeases(deviceId: String, credential: String, receipts: List<QueueLeaseReceipt>): Int {
            events += "release"
            return receipts.size
        }
    }

    private class FakeLocalStore(private val events: MutableList<String>) : CloudQueueLocalStore {
        private data class Entry(var job: GatewayQueueJob, var acked: Boolean)
        private val rows = linkedMapOf<String, Entry>()
        var failPersistence = false
        private var lastSync: Long? = null

        override fun persist(deviceId: String, job: GatewayQueueJob): PersistResult {
            events += "persist"
            if (failPersistence) throw IllegalStateException("disk full")
            val existing = rows[job.jobId]
            val result = CloudJobReconciler.decide(existing?.let { CloudJobReconciler.StoredShape(it.job, it.acked) }, job)
            when (result) {
                PersistResult.INSERTED -> rows[job.jobId] = Entry(job, false)
                PersistResult.SAME_LEASE -> Unit
                PersistResult.NEWER_LEASE -> rows[job.jobId] = Entry(job, false)
            }
            return result
        }

        override fun pendingAckReceipts(deviceId: String, limit: Int): List<QueueLeaseReceipt> = rows.values.filter { !it.acked }.take(limit).map { it.job.receipt() }

        override fun markAcknowledged(deviceId: String, receipts: List<QueueLeaseReceipt>): Int {
            events += "markAck"
            var count = 0
            receipts.forEach { r ->
                val e = rows[r.jobId]
                if (e != null && e.job.leaseToken == r.leaseToken && e.job.leaseVersion == r.leaseVersion) {
                    e.acked = true; count++
                }
            }
            return count
        }

        override fun counts(deviceId: String): CloudQueueCounts {
            val pending = rows.values.count { !it.acked }
            return CloudQueueCounts(rows.size, pending, rows.size - pending, lastSync)
        }

        override fun recordSync(deviceId: String, atMillis: Long, error: String?) { lastSync = atMillis }
        override fun exportDiagnostics(deviceId: String?): String = "{}"
    }
}
