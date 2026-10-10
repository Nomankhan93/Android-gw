package com.bulktext.gateway.inventory

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.SubscriptionInfo
import com.bulktext.gateway.BuildConfig
import com.bulktext.gateway.Telephony
import com.bulktext.gateway.cloud.DeviceInventorySnapshot
import com.bulktext.gateway.cloud.SimInventorySnapshot

class AndroidDeviceInventoryProvider(private val context: Context) {
    fun canReadSims(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun snapshot(): DeviceInventorySnapshot {
        check(canReadSims()) { "Phone permission is required before SIM inventory can be reported." }

        val active = Telephony.sims(context)
        require(active.size <= 8) { "This device reports more SIM subscriptions than BulkText currently supports." }

        val sims = active.map { toSnapshot(it) }
        return DeviceInventorySnapshot(
            manufacturer = Build.MANUFACTURER?.trim()?.takeIf { it.isNotEmpty() }?.take(80),
            model = Build.MODEL?.trim()?.takeIf { it.isNotEmpty() }?.take(120),
            androidRelease = Build.VERSION.RELEASE?.trim()?.takeIf { it.isNotEmpty() }?.take(40),
            sdkInt = Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME.take(40),
            appVersionCode = BuildConfig.VERSION_CODE,
            batteryPercent = batteryPercent(),
            sims = sims
        )
    }

    private fun toSnapshot(info: SubscriptionInfo): SimInventorySnapshot {
        require(info.subscriptionId >= 0) { "Android returned an invalid SIM subscription identifier." }
        require(info.simSlotIndex in 0..7) { "Android returned an unsupported SIM slot index." }

        val carrier = info.carrierName?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(100)
        val display = info.displayName?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(100)
        val country = info.countryIso?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.take(8)
        val embedded = Build.VERSION.SDK_INT >= 28 && info.isEmbedded
        val carrierId = if (Build.VERSION.SDK_INT >= 29) info.carrierId else -1

        return SimInventorySnapshot(
            subscriptionId = info.subscriptionId,
            slotIndex = info.simSlotIndex,
            carrierName = carrier,
            displayName = display,
            countryIso = country,
            isEmbedded = embedded,
            simIdentityHash = SimIdentityHasher.hash(
                subscriptionId = info.subscriptionId,
                slotIndex = info.simSlotIndex,
                carrierId = carrierId,
                carrierName = carrier,
                countryIso = country,
                isEmbedded = embedded
            )
        )
    }

    private fun batteryPercent(): Int? {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        val capacity = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return capacity.takeIf { it in 0..100 }
    }
}
