package com.bulktext.gateway.cloud

data class DeviceInventorySnapshot(
    val manufacturer: String?,
    val model: String?,
    val androidRelease: String?,
    val sdkInt: Int?,
    val appVersion: String?,
    val appVersionCode: Int?,
    val batteryPercent: Int?,
    val sims: List<SimInventorySnapshot>
)

data class SimInventorySnapshot(
    val subscriptionId: Int,
    val slotIndex: Int,
    val carrierName: String?,
    val displayName: String?,
    val countryIso: String?,
    val isEmbedded: Boolean,
    val simIdentityHash: String?
)

enum class BindingStatus(val wireValue: String) {
    UNBOUND("unbound"),
    READY("ready"),
    MISSING("missing");

    companion object {
        fun fromWire(value: String): BindingStatus = values().firstOrNull { it.wireValue == value }
            ?: throw CloudException.ServerContractException("Inventory response contains an unknown binding status")
    }
}

data class InventoryReportResult(
    val deviceId: String,
    val organizationId: String?,
    val serverTime: Long,
    val boundSimId: String?,
    val boundSubscriptionId: Int?,
    val boundSlotIndex: Int?,
    val boundCarrierName: String?,
    val bindingStatus: BindingStatus
)
