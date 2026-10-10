package com.bulktext.gateway.inventory

data class CloudBoundSim(
    val simId: String,
    val subscriptionId: Int,
    val slotIndex: Int,
    val carrierName: String?
)

sealed class GatewayInventoryState {
    object NotReported : GatewayInventoryState()
    object Reporting : GatewayInventoryState()
    object Unpaired : GatewayInventoryState()
    object PermissionRequired : GatewayInventoryState()
    object Offline : GatewayInventoryState()
    object CredentialRejected : GatewayInventoryState()
    data class Unbound(val serverTime: Long) : GatewayInventoryState()
    data class Ready(val binding: CloudBoundSim, val serverTime: Long) : GatewayInventoryState()
    data class Missing(val binding: CloudBoundSim?, val serverTime: Long) : GatewayInventoryState()
    data class Error(val message: String) : GatewayInventoryState()
}
