package com.bulktext.gateway.cloud

interface GatewayInventoryApi {
    @Throws(Exception::class)
    fun reportGatewayDeviceInventory(
        deviceId: String,
        credential: String,
        inventory: DeviceInventorySnapshot
    ): InventoryReportResult
}
