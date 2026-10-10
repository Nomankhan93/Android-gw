package com.bulktext.gateway.identity

interface CredentialStore {
    fun saveCredential(
        deviceId: String,
        credential: String,
        version: Int,
        expiresAt: Long,
        deviceName: String
    ): Boolean

    fun getDeviceId(): String?
    fun getDeviceName(): String
    fun getCredential(): String?
    fun getCredentialVersion(): Int
    fun getExpiresAt(): Long
    fun clear(): Boolean
}
