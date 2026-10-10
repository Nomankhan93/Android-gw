package com.bulktext.gateway.inventory

import com.bulktext.gateway.cloud.BindingStatus
import com.bulktext.gateway.cloud.CloudException
import com.bulktext.gateway.cloud.DeviceInventorySnapshot
import com.bulktext.gateway.cloud.GatewayInventoryApi
import com.bulktext.gateway.cloud.InventoryReportResult
import com.bulktext.gateway.identity.CredentialStore

class GatewayInventoryRepository(
    private val credentialStore: CredentialStore,
    private val api: GatewayInventoryApi
) {
    fun report(inventory: DeviceInventorySnapshot): GatewayInventoryState {
        val deviceId = credentialStore.getDeviceId() ?: return GatewayInventoryState.Unpaired
        val credential = credentialStore.getCredential() ?: return GatewayInventoryState.Unpaired
        if (credential.isBlank()) return GatewayInventoryState.Unpaired

        return try {
            val result = api.reportGatewayDeviceInventory(deviceId, credential, inventory)
            toState(result, inventory)
        } catch (_: CloudException.NetworkException) {
            GatewayInventoryState.Offline
        } catch (_: CloudException.CredentialRejectedException) {
            GatewayInventoryState.CredentialRejected
        } catch (_: CloudException.ConfigurationException) {
            GatewayInventoryState.Error("BulkText cloud configuration is missing.")
        } catch (_: CloudException.ServerContractException) {
            GatewayInventoryState.Error("BulkText cloud returned an unexpected SIM inventory response.")
        } catch (e: CloudException.HttpException) {
            GatewayInventoryState.Error(e.message ?: "BulkText cloud inventory request failed.")
        } catch (e: Exception) {
            GatewayInventoryState.Error(e.message ?: "Unable to report phone and SIM inventory.")
        }
    }

    internal fun toState(result: InventoryReportResult, inventory: DeviceInventorySnapshot): GatewayInventoryState =
        when (result.bindingStatus) {
            BindingStatus.UNBOUND -> GatewayInventoryState.Unbound(result.serverTime)
            BindingStatus.READY -> {
                val binding = requireBinding(result)
                val matchingLocalSim = inventory.sims.any {
                    it.subscriptionId == binding.subscriptionId && it.slotIndex == binding.slotIndex
                }
                // Never substitute another active SIM if the authoritative binding is absent.
                if (matchingLocalSim) GatewayInventoryState.Ready(binding, result.serverTime)
                else GatewayInventoryState.Missing(binding, result.serverTime)
            }
            BindingStatus.MISSING -> GatewayInventoryState.Missing(optionalBinding(result), result.serverTime)
        }

    private fun requireBinding(result: InventoryReportResult): CloudBoundSim {
        val simId = result.boundSimId
            ?: throw CloudException.ServerContractException("Ready SIM binding is missing sim id")
        val subscriptionId = result.boundSubscriptionId
            ?: throw CloudException.ServerContractException("Ready SIM binding is missing subscription id")
        val slotIndex = result.boundSlotIndex
            ?: throw CloudException.ServerContractException("Ready SIM binding is missing slot index")
        return CloudBoundSim(simId, subscriptionId, slotIndex, result.boundCarrierName)
    }

    private fun optionalBinding(result: InventoryReportResult): CloudBoundSim? {
        val simId = result.boundSimId ?: return null
        val subscriptionId = result.boundSubscriptionId ?: return null
        val slotIndex = result.boundSlotIndex ?: return null
        return CloudBoundSim(simId, subscriptionId, slotIndex, result.boundCarrierName)
    }
}
