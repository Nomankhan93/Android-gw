package com.bulktext.gateway

import android.app.Application
import android.content.Intent
import android.os.Build
import com.bulktext.gateway.cloud.SupabaseGatewayApi
import com.bulktext.gateway.identity.GatewayIdentityRepository
import com.bulktext.gateway.identity.InstallationIdStore
import com.bulktext.gateway.identity.SecureCredentialStore
import com.bulktext.gateway.inventory.AndroidDeviceInventoryProvider
import com.bulktext.gateway.inventory.GatewayInventoryRepository
import com.bulktext.gateway.queue.CloudDeliveryRepository
import com.bulktext.gateway.queue.CloudQueueRepository
import com.bulktext.gateway.queue.SQLiteCloudQueueStore

class GatewayApp : Application() {
    lateinit var store: Store
    lateinit var installationIdStore: InstallationIdStore
    lateinit var secureCredentialStore: SecureCredentialStore
    lateinit var supabaseGatewayApi: SupabaseGatewayApi
    lateinit var identityRepository: GatewayIdentityRepository
    lateinit var inventoryProvider: AndroidDeviceInventoryProvider
    lateinit var inventoryRepository: GatewayInventoryRepository
    lateinit var cloudQueueStore: SQLiteCloudQueueStore
    lateinit var cloudQueueRepository: CloudQueueRepository
    lateinit var cloudDeliveryRepository: CloudDeliveryRepository
    lateinit var backgroundGatewayPreferences: BackgroundGatewayPreferences

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        store.recover()

        installationIdStore = InstallationIdStore(this)
        secureCredentialStore = SecureCredentialStore(this)
        supabaseGatewayApi = SupabaseGatewayApi()
        identityRepository = GatewayIdentityRepository(installationIdStore, secureCredentialStore, supabaseGatewayApi)
        inventoryProvider = AndroidDeviceInventoryProvider(this)
        inventoryRepository = GatewayInventoryRepository(secureCredentialStore, supabaseGatewayApi)
        backgroundGatewayPreferences = BackgroundGatewayPreferences(this)
        cloudQueueStore = SQLiteCloudQueueStore(this)
        cloudQueueRepository = CloudQueueRepository(secureCredentialStore, supabaseGatewayApi, cloudQueueStore)
        cloudDeliveryRepository = CloudDeliveryRepository(secureCredentialStore, supabaseGatewayApi, cloudQueueStore, backgroundGatewayPreferences)

        val recoveredUnknown = cloudQueueStore.recoverInterruptedSubmissions()
        if (recoveredUnknown > 0) {
            backgroundGatewayPreferences.pause("Gateway paused: an interrupted SUBMITTING job recovered as UNKNOWN. Use Web recovery; no automatic retry.")
        }

        if (secureCredentialStore.getDeviceId()?.let { cloudQueueStore.pendingAttemptEvents(it, 1).isNotEmpty() } == true) CallbackSyncService.schedule(this)

        // 0.18 keeps the service alive while paused so it can receive explicit Web
        // recovery requests and flush callback outbox events without opening the app.
        if (backgroundGatewayPreferences.isEnabled()) {
            GatewayWatchdogService.schedule(this)
            try {
                val intent = Intent(this, BackgroundGatewayService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            } catch (_: Exception) {
                backgroundGatewayPreferences.setStatus("Gateway is enabled but Android did not start the background service. Open the app once.")
            }
        } else {
            GatewayWatchdogService.cancel(this)
        }
    }
}
