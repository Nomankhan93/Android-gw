package com.bulktext.gateway.queue

import com.bulktext.gateway.cloud.CloudException
import com.bulktext.gateway.cloud.GatewayQueueApi
import com.bulktext.gateway.cloud.GatewayQueueJob
import com.bulktext.gateway.identity.CredentialStore

class CloudQueueRepository(
    private val credentialStore: CredentialStore,
    private val api: GatewayQueueApi,
    private val localStore: CloudQueueLocalStore,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    fun countsForCurrentDevice(): CloudQueueCounts {
        val deviceId = credentialStore.getDeviceId() ?: return CloudQueueCounts(0, 0, 0, null)
        return localStore.counts(deviceId)
    }

    fun diagnosticsForCurrentDevice(): String = localStore.exportDiagnostics(credentialStore.getDeviceId())

    fun sync(identity: CloudQueueExecutionIdentity, limit: Int = 20): CloudQueueState {
        val deviceId = credentialStore.getDeviceId()
            ?: return CloudQueueState.Error("Pair this phone before syncing cloud jobs.", CloudQueueCounts(0, 0, 0, null))
        val credential = credentialStore.getCredential()
            ?: return CloudQueueState.Error("Gateway credential is unavailable.", localStore.counts(deviceId))
        if (deviceId != identity.deviceId) {
            return CloudQueueState.Error("Cloud queue identity does not match the paired gateway device.", localStore.counts(deviceId))
        }
        val expiresAt = credentialStore.getExpiresAt()
        if (expiresAt > 0L && expiresAt <= now()) {
            return CloudQueueState.CredentialRejected(localStore.counts(deviceId))
        }

        var ackedThisSync = 0
        try {
            val pending = localStore.pendingAckReceipts(deviceId, 25)
            if (pending.isNotEmpty()) {
                try {
                    val accepted = api.acknowledgeGatewayMessageJobs(deviceId, credential, pending)
                    if (accepted != pending.size) {
                        throw CloudException.ServerContractException("Queue ACK count did not match the submitted receipt count")
                    }
                    val marked = localStore.markAcknowledged(deviceId, pending)
                    if (marked != pending.size) throw CloudQueueIntegrityException("Server accepted queue ACK but local ACK state could not be fully persisted")
                    ackedThisSync += marked
                } catch (_: CloudException.QueueLeaseConflictException) {
                    // Keep durable local jobs. A later claim can return the same stable
                    // job ids with newer lease versions after server-side expiry/requeue.
                }
            }

            val jobs = api.claimGatewayMessageJobs(deviceId, credential, limit.coerceIn(1, 25))
            if (jobs.isEmpty()) {
                val at = now()
                recordSyncSafely(deviceId, at, null)
                return CloudQueueState.Ready(0, ackedThisSync, localStore.counts(deviceId))
            }

            verifyBatchIdentity(identity, jobs)
            val persistedReceipts = mutableListOf<com.bulktext.gateway.cloud.QueueLeaseReceipt>()
            val unpersistedReceipts = jobs.map { it.receipt() }.toMutableList()
            try {
                jobs.forEach { job ->
                    localStore.persist(deviceId, job)
                    persistedReceipts += job.receipt()
                    unpersistedReceipts.remove(job.receipt())
                }
            } catch (e: Exception) {
                // Only work not durably accepted may be released. Persisted work stays
                // local and must recover through ACK/re-lease reconciliation.
                if (unpersistedReceipts.isNotEmpty()) {
                    try { api.releaseGatewayMessageJobLeases(deviceId, credential, unpersistedReceipts) } catch (_: Exception) { }
                }
                recordSyncSafely(deviceId, now(), "Local persistence failed; no ACK sent")
                return CloudQueueState.Error(e.message ?: "Unable to persist cloud jobs locally.", localStore.counts(deviceId))
            }

            if (persistedReceipts.isNotEmpty()) {
                val accepted = api.acknowledgeGatewayMessageJobs(deviceId, credential, persistedReceipts)
                if (accepted != persistedReceipts.size) {
                    throw CloudException.ServerContractException("Queue ACK count did not match the persisted receipt count")
                }
                val marked = localStore.markAcknowledged(deviceId, persistedReceipts)
                if (marked != persistedReceipts.size) throw CloudQueueIntegrityException("Server accepted queue ACK but local ACK state could not be fully persisted")
                ackedThisSync += marked
            }

            recordSyncSafely(deviceId, now(), null)
            return CloudQueueState.Ready(jobs.size, ackedThisSync, localStore.counts(deviceId))
        } catch (_: CloudException.NetworkException) {
            recordSyncSafely(deviceId, now(), "Network unavailable")
            return CloudQueueState.Offline(localStore.counts(deviceId))
        } catch (_: CloudException.CredentialRejectedException) {
            recordSyncSafely(deviceId, now(), "Gateway credential rejected")
            return CloudQueueState.CredentialRejected(localStore.counts(deviceId))
        } catch (e: CloudException.QueueBlockedException) {
            recordSyncSafely(deviceId, now(), e.message)
            return CloudQueueState.SimBlocked(e.message ?: "Exact Web-selected SIM is not ready.", localStore.counts(deviceId))
        } catch (e: CloudException.QueueLeaseConflictException) {
            recordSyncSafely(deviceId, now(), "Queue lease changed; sync again to reconcile")
            return CloudQueueState.Error("Queue lease changed on the server. The durable local job was retained; sync again to reconcile.", localStore.counts(deviceId))
        } catch (e: CloudQueueIntegrityException) {
            recordSyncSafely(deviceId, now(), "Cloud job integrity error")
            return CloudQueueState.Error(e.message ?: "Cloud job integrity check failed.", localStore.counts(deviceId))
        } catch (e: CloudException.ConfigurationException) {
            return CloudQueueState.Error("BulkText cloud configuration is missing.", localStore.counts(deviceId))
        } catch (e: CloudException.ServerContractException) {
            recordSyncSafely(deviceId, now(), "Unexpected cloud queue response")
            return CloudQueueState.Error(e.message ?: "BulkText cloud returned an unexpected queue response.", localStore.counts(deviceId))
        } catch (e: CloudException.HttpException) {
            recordSyncSafely(deviceId, now(), e.message)
            return CloudQueueState.Error(e.message ?: "BulkText cloud queue request failed.", localStore.counts(deviceId))
        } catch (e: Exception) {
            recordSyncSafely(deviceId, now(), e.message)
            return CloudQueueState.Error(e.message ?: "Cloud queue synchronization failed.", localStore.counts(deviceId))
        }
    }

    private fun recordSyncSafely(deviceId: String, atMillis: Long, error: String?) {
        try { localStore.recordSync(deviceId, atMillis, error) } catch (_: Exception) { }
    }

    internal fun verifyBatchIdentity(identity: CloudQueueExecutionIdentity, jobs: List<GatewayQueueJob>) {
        jobs.forEach { job ->
            if (
                job.gatewaySimId != identity.gatewaySimId ||
                job.simSubscriptionId != identity.subscriptionId ||
                job.simSlotIndex != identity.slotIndex ||
                job.simIdentityHash != identity.simIdentityHash
            ) {
                throw CloudQueueIntegrityException("Cloud queue job does not match the exact current Web-bound SIM identity")
            }
        }
    }
}
