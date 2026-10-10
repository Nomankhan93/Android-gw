package com.bulktext.gateway.cloud

interface GatewayApi {
    @Throws(Exception::class)
    fun claimGatewayPairing(pairingCode: String, deviceName: String, installationId: String): ClaimPairingResult

    @Throws(Exception::class)
    fun authenticateGatewayDevice(deviceId: String, credential: String): AuthDeviceResult

    @Throws(Exception::class)
    fun rotateGatewayDeviceCredential(deviceId: String, currentCredential: String): RotateCredentialResult
}
