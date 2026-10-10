package com.bulktext.gateway

data class CloudSendPermit(val generation: Long, val automatic: Boolean)

/** Serializes the final SMS boundary with durable operator stop decisions. */
class CloudSubmissionGate(
    private val generation: () -> Long,
    private val enabled: () -> Boolean
) {
    @Synchronized
    fun capture(automatic: Boolean): CloudSendPermit? =
        CloudSendPermit(generation(), automatic).takeIf { isAllowed(it) }

    @Synchronized
    fun isAllowed(permit: CloudSendPermit): Boolean =
        permit.generation == generation() && (!permit.automatic || enabled())

    @Synchronized
    fun changeAuthorization(change: () -> Unit) = change()

    /** No network calls here: only local SUBMITTING persistence and SmsManager. */
    @Synchronized
    fun runIfAllowed(permit: CloudSendPermit, submit: () -> Unit): Boolean {
        if (!isAllowed(permit)) return false
        submit()
        return true
    }
}
