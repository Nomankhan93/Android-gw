package com.bulktext.gateway

import com.bulktext.gateway.cloud.BindingStatus
import com.bulktext.gateway.cloud.CloudException
import com.bulktext.gateway.cloud.DeviceInventorySnapshot
import com.bulktext.gateway.cloud.GatewayInventoryApi
import com.bulktext.gateway.cloud.InventoryReportResult
import com.bulktext.gateway.cloud.SimInventorySnapshot
import com.bulktext.gateway.cloud.SupabaseGatewayApi
import com.bulktext.gateway.identity.CredentialStore
import com.bulktext.gateway.inventory.GatewayInventoryRepository
import com.bulktext.gateway.inventory.GatewayInventoryState
import com.bulktext.gateway.inventory.SimIdentityHasher
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GatewayInventoryTest {
    private val inventory = DeviceInventorySnapshot(
        manufacturer = "vivo",
        model = "V2109",
        androidRelease = "13",
        sdkInt = 33,
        appVersion = "0.6.0",
        appVersionCode = 7,
        batteryPercent = 73,
        sims = listOf(
            SimInventorySnapshot(11, 0, "Ufone", "Ufone", "pk", false, "a".repeat(64)),
            SimInventorySnapshot(22, 1, "Jazz", "Jazz", "pk", false, "b".repeat(64))
        )
    )

    @Test
    fun inventoryPayloadUsesExactRpcKeysAndNoSubscriberSecrets() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")
        val payload = api.buildInventoryPayload("dev-1", "secret-key", inventory)
        assertEquals(setOf("p_device_id", "p_credential", "p_device", "p_sims"), payload.keys().asSequence().toSet())
        val device = payload.getJSONObject("p_device")
        assertEquals("vivo", device.getString("manufacturer"))
        assertEquals(7, device.getInt("appVersionCode"))
        val sims = payload.getJSONArray("p_sims")
        assertEquals(2, sims.length())
        val first = sims.getJSONObject(0)
        assertEquals(11, first.getInt("subscriptionId"))
        assertFalse(first.has("phoneNumber"))
        assertFalse(first.has("iccId"))
        assertFalse(first.has("imsi"))
        assertFalse(first.has("imei"))
    }

    @Test
    fun inventoryParserAcceptsBackendBindingShapes() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")
        val ready = JSONObject(
            """{
              "device_id":"dev-1",
              "organization_id":"org-1",
              "server_time":"2026-10-01T15:00:00Z",
              "bound_sim_id":"sim-22",
              "bound_subscription_id":22,
              "bound_slot_index":1,
              "bound_carrier_name":"Jazz",
              "binding_status":"ready"
            }""".trimIndent()
        )
        val result = api.parseInventoryResult(ready, "dev-1")
        assertEquals(BindingStatus.READY, result.bindingStatus)
        assertEquals(22, result.boundSubscriptionId)

        val unbound = JSONObject(
            """{
              "device_id":"dev-1",
              "organization_id":"org-1",
              "server_time":"2026-10-01T15:00:00Z",
              "bound_sim_id":null,
              "bound_subscription_id":null,
              "bound_slot_index":null,
              "bound_carrier_name":null,
              "binding_status":"unbound"
            }""".trimIndent()
        )
        assertEquals(BindingStatus.UNBOUND, api.parseInventoryResult(unbound, "dev-1").bindingStatus)
    }

    @Test
    fun readyBindingRequiresAuthoritativeFieldsAndMatchingDevice() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")
        val missingFields = JSONObject(
            """{
              "device_id":"dev-1",
              "server_time":"2026-10-01T15:00:00Z",
              "binding_status":"ready"
            }""".trimIndent()
        )
        assertThrows(CloudException.ServerContractException::class.java) {
            api.parseInventoryResult(missingFields, "dev-1")
        }
        val differentDevice = JSONObject(
            """{
              "device_id":"dev-2",
              "server_time":"2026-10-01T15:00:00Z",
              "binding_status":"unbound"
            }""".trimIndent()
        )
        assertThrows(CloudException.ServerContractException::class.java) {
            api.parseInventoryResult(differentDevice, "dev-1")
        }
    }

    @Test
    fun repositoryNeverFallsBackWhenBoundSimIsMissing() {
        val store = FakeCredentialStore("dev-1", "credential-12345678901234567890")
        val api = FakeInventoryApi().apply {
            result = report(BindingStatus.READY, sub = 99, slot = 1)
        }
        val state = GatewayInventoryRepository(store, api).report(inventory)
        assertTrue(state is GatewayInventoryState.Missing)
        state as GatewayInventoryState.Missing
        assertEquals(99, state.binding?.subscriptionId)
        // SIM 11/22 are present but neither is silently selected as a replacement.
    }

    @Test
    fun repositoryReturnsReadyOnlyForExactBoundSubscriptionAndSlot() {
        val store = FakeCredentialStore("dev-1", "credential-12345678901234567890")
        val api = FakeInventoryApi().apply {
            result = report(BindingStatus.READY, sub = 22, slot = 1)
        }
        val state = GatewayInventoryRepository(store, api).report(inventory)
        assertTrue(state is GatewayInventoryState.Ready)
        state as GatewayInventoryState.Ready
        assertEquals(22, state.binding.subscriptionId)
        assertEquals(1, state.binding.slotIndex)
    }

    @Test
    fun repositorySeparatesUnboundOfflineAndRejectedCredential() {
        val store = FakeCredentialStore("dev-1", "credential-12345678901234567890")
        val api = FakeInventoryApi().apply { result = report(BindingStatus.UNBOUND) }
        assertTrue(GatewayInventoryRepository(store, api).report(inventory) is GatewayInventoryState.Unbound)

        api.networkError = true
        assertEquals(GatewayInventoryState.Offline, GatewayInventoryRepository(store, api).report(inventory))

        api.networkError = false
        api.rejected = true
        assertEquals(GatewayInventoryState.CredentialRejected, GatewayInventoryRepository(store, api).report(inventory))
    }

    @Test
    fun simIdentityHashIsDeterministicAndSha256Sized() {
        val a = SimIdentityHasher.hash(22, 1, 10, "Jazz", "pk", false)
        val b = SimIdentityHasher.hash(22, 1, 10, "Jazz", "pk", false)
        val c = SimIdentityHasher.hash(11, 0, 20, "Ufone", "pk", false)
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertTrue(a.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun idleCacheExpiresAndPreSendAlwaysRefreshesBinding() {
        var now = 0L
        val store = FakeCredentialStore("dev-1", "credential")
        val api = FakeInventoryApi().apply { result = report(BindingStatus.READY, 22, 1) }
        val repo = GatewayInventoryRepository(store, api, { now })
        assertTrue(repo.report(inventory, allowRecent = true) is GatewayInventoryState.Ready)
        api.result = report(BindingStatus.UNBOUND)
        now = 59999
        assertTrue(repo.report(inventory, allowRecent = true) is GatewayInventoryState.Ready)
        assertTrue(repo.report(inventory) is GatewayInventoryState.Unbound)
        assertTrue(repo.report(inventory, allowRecent = true) is GatewayInventoryState.Unbound)
        api.result = report(BindingStatus.READY, 22, 1)
        repo.report(inventory)
        now += 60000
        api.result = report(BindingStatus.UNBOUND)
        assertTrue(repo.report(inventory, allowRecent = true) is GatewayInventoryState.Unbound)
    }

    @Test
    fun changedSnapshotAndCredentialInvalidateIdleCache() {
        val store = FakeCredentialStore("dev-1", "first")
        val api = FakeInventoryApi().apply { result = report(BindingStatus.READY, 22, 1) }
        val repo = GatewayInventoryRepository(store, api, { 0L })
        repo.report(inventory)
        api.result = report(BindingStatus.UNBOUND)
        assertTrue(repo.report(inventory.copy(sims = emptyList()), allowRecent = true) is GatewayInventoryState.Unbound)
        api.result = report(BindingStatus.READY, 22, 1)
        repo.report(inventory)
        store.saveCredential("dev-1", "second", 2, Long.MAX_VALUE, "Phone")
        api.rejected = true
        assertEquals(GatewayInventoryState.CredentialRejected, repo.report(inventory, allowRecent = true))
    }

    private fun report(status: BindingStatus, sub: Int? = null, slot: Int? = null) = InventoryReportResult(
        deviceId = "dev-1",
        organizationId = "org-1",
        serverTime = 1_760_000_000_000L,
        boundSimId = if (sub == null) null else "sim-$sub",
        boundSubscriptionId = sub,
        boundSlotIndex = slot,
        boundCarrierName = if (sub == 22) "Jazz" else if (sub == 99) "Other" else null,
        bindingStatus = status
    )

    private class FakeInventoryApi : GatewayInventoryApi {
        var result: InventoryReportResult = InventoryReportResult("dev-1", "org-1", 1L, null, null, null, null, BindingStatus.UNBOUND)
        var networkError = false
        var rejected = false
        override fun reportGatewayDeviceInventory(deviceId: String, credential: String, inventory: DeviceInventorySnapshot): InventoryReportResult {
            if (networkError) throw CloudException.NetworkException("offline")
            if (rejected) throw CloudException.CredentialRejectedException()
            return result
        }
    }

    private class FakeCredentialStore(
        private var deviceId: String?,
        private var credential: String?
    ) : CredentialStore {
        override fun saveCredential(deviceId: String, credential: String, version: Int, expiresAt: Long, deviceName: String): Boolean {
            this.deviceId = deviceId; this.credential = credential; return true
        }
        override fun getDeviceId(): String? = deviceId
        override fun getDeviceName(): String = "Phone"
        override fun getCredential(): String? = credential
        override fun getCredentialVersion(): Int = 1
        override fun getExpiresAt(): Long = Long.MAX_VALUE
        override fun clear(): Boolean { deviceId = null; credential = null; return true }
    }
}
