package com.bulktext.gateway.identity

sealed class GatewayIdentityState {
    object Unpaired : GatewayIdentityState()
    object Pairing : GatewayIdentityState()
    data class Paired(
        val deviceId: String,
        val deviceName: String,
        val credentialVersion: Int,
        val expiresAt: Long
    ) : GatewayIdentityState()
    object Offline : GatewayIdentityState()
    object CredentialExpired : GatewayIdentityState()
    object CredentialRejected : GatewayIdentityState()
    data class Error(val message: String) : GatewayIdentityState()
}
