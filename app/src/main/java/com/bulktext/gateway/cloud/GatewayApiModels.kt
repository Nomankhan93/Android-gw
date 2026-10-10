package com.bulktext.gateway.cloud

data class ClaimPairingResult(
    val deviceId: String,
    val organizationId: String?,
    val deviceName: String,
    val credential: String,
    val credentialVersion: Int,
    val credentialExpiresAt: Long,
    val pairedAt: Long
) {
    override fun toString(): String =
        "ClaimPairingResult(deviceId='$deviceId', deviceName='$deviceName', credential='[REDACTED]', credentialVersion=$credentialVersion, expiresAt=$credentialExpiresAt)"
}

data class AuthDeviceResult(
    val deviceId: String,
    val organizationId: String?,
    val deviceName: String,
    val credentialVersion: Int,
    val credentialExpiresAt: Long,
    val serverTime: Long
)

data class RotateCredentialResult(
    val deviceId: String,
    val credential: String,
    val credentialVersion: Int,
    val credentialExpiresAt: Long
) {
    override fun toString(): String =
        "RotateCredentialResult(deviceId='$deviceId', credential='[REDACTED]', credentialVersion=$credentialVersion, credentialExpiresAt=$credentialExpiresAt)"
}
