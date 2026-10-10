package com.bulktext.gateway.identity

import com.bulktext.gateway.cloud.CloudException
import com.bulktext.gateway.cloud.GatewayApi

class GatewayIdentityRepository(
    private val installationIdProvider: InstallationIdProvider,
    private val credentialStore: CredentialStore,
    private val api: GatewayApi
) {

    fun getInstallationId(): String = installationIdProvider.getInstallationId()

    @Synchronized
    fun getCurrentState(): GatewayIdentityState {
        val deviceId = credentialStore.getDeviceId() ?: return GatewayIdentityState.Unpaired
        val credential = credentialStore.getCredential() ?: return GatewayIdentityState.Unpaired
        if (credential.isBlank()) return GatewayIdentityState.Unpaired

        val expiresAt = credentialStore.getExpiresAt()
        if (expiresAt <= 0L) return GatewayIdentityState.Error("Stored gateway credential metadata is incomplete.")
        if (expiresAt <= System.currentTimeMillis()) return GatewayIdentityState.CredentialExpired

        return GatewayIdentityState.Paired(
            deviceId = deviceId,
            deviceName = credentialStore.getDeviceName(),
            credentialVersion = credentialStore.getCredentialVersion(),
            expiresAt = expiresAt
        )
    }

    fun normalizePairingCode(rawCode: String): String =
        rawCode.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")

    fun pair(rawPairingCode: String, deviceName: String): GatewayIdentityState {
        val code = normalizePairingCode(rawPairingCode)
        if (code.length != 12) {
            return GatewayIdentityState.Error("Pairing code must be 12 characters.")
        }

        return try {
            val installationId = installationIdProvider.getInstallationId()
            val result = api.claimGatewayPairing(
                pairingCode = code,
                deviceName = deviceName.trim().ifBlank { "Android Gateway" },
                installationId = installationId
            )
            val saved = credentialStore.saveCredential(
                deviceId = result.deviceId,
                credential = result.credential,
                version = result.credentialVersion,
                expiresAt = result.credentialExpiresAt,
                deviceName = result.deviceName
            )
            if (!saved) {
                return GatewayIdentityState.Error(
                    "Phone paired with BulkText, but the gateway credential could not be stored securely. Revoke this phone from the web app before pairing again."
                )
            }
            GatewayIdentityState.Paired(
                deviceId = result.deviceId,
                deviceName = result.deviceName,
                credentialVersion = result.credentialVersion,
                expiresAt = result.credentialExpiresAt
            )
        } catch (e: CloudException.NetworkException) {
            GatewayIdentityState.Error("Network unavailable. Check your internet connection and try pairing again.")
        } catch (e: CloudException.InvalidPairingCodeException) {
            GatewayIdentityState.Error(e.message ?: "Pairing code is invalid or expired.")
        } catch (e: CloudException.AlreadyPairedException) {
            GatewayIdentityState.Error(e.message ?: "This phone installation is already paired.")
        } catch (e: CloudException.ConfigurationException) {
            GatewayIdentityState.Error(e.message ?: "BulkText cloud configuration is missing.")
        } catch (e: CloudException.ServerContractException) {
            GatewayIdentityState.Error("BulkText cloud returned an unexpected response. Try again or update the gateway app.")
        } catch (e: CloudException.HttpException) {
            GatewayIdentityState.Error(e.message ?: "BulkText cloud request failed.")
        } catch (e: Exception) {
            GatewayIdentityState.Error(e.message ?: "Failed to pair this phone")
        }
    }

    fun authenticate(): GatewayIdentityState {
        val deviceId = credentialStore.getDeviceId() ?: return GatewayIdentityState.Unpaired
        val credential = credentialStore.getCredential() ?: return GatewayIdentityState.Unpaired
        val localExpiresAt = credentialStore.getExpiresAt()
        if (localExpiresAt > 0L && localExpiresAt <= System.currentTimeMillis()) {
            return GatewayIdentityState.CredentialExpired
        }

        return try {
            val result = api.authenticateGatewayDevice(deviceId, credential)
            val saved = credentialStore.saveCredential(
                deviceId = result.deviceId,
                credential = credential,
                version = result.credentialVersion,
                expiresAt = result.credentialExpiresAt,
                deviceName = result.deviceName
            )
            if (!saved) {
                GatewayIdentityState.Error("Gateway authenticated, but refreshed credential metadata could not be stored securely.")
            } else {
                GatewayIdentityState.Paired(
                    deviceId = result.deviceId,
                    deviceName = result.deviceName,
                    credentialVersion = result.credentialVersion,
                    expiresAt = result.credentialExpiresAt
                )
            }
        } catch (e: CloudException.NetworkException) {
            // Network failure does not invalidate or erase the local pairing.
            GatewayIdentityState.Offline
        } catch (e: CloudException.CredentialRejectedException) {
            GatewayIdentityState.CredentialRejected
        } catch (e: CloudException.ConfigurationException) {
            GatewayIdentityState.Error(e.message ?: "BulkText cloud configuration is missing.")
        } catch (e: CloudException.ServerContractException) {
            GatewayIdentityState.Error("BulkText cloud returned an unexpected authentication response.")
        } catch (e: CloudException.HttpException) {
            GatewayIdentityState.Error(e.message ?: "BulkText cloud request failed.")
        } catch (e: Exception) {
            GatewayIdentityState.Error(e.message ?: "Gateway authentication failed")
        }
    }

    /**
     * Rotates the server credential and persists the replacement synchronously.
     * If the server rotation succeeds but local persistence fails, the previous
     * credential is already invalid server-side. We therefore clear local
     * pairing state instead of retaining a stale credential.
     */
    fun rotateCredential(): Boolean {
        val deviceId = credentialStore.getDeviceId() ?: return false
        val currentCredential = credentialStore.getCredential() ?: return false
        val deviceName = credentialStore.getDeviceName()

        return try {
            val result = api.rotateGatewayDeviceCredential(deviceId, currentCredential)
            val saved = credentialStore.saveCredential(
                deviceId = result.deviceId,
                credential = result.credential,
                version = result.credentialVersion,
                expiresAt = result.credentialExpiresAt,
                deviceName = deviceName
            )
            if (!saved) {
                credentialStore.clear()
                false
            } else {
                true
            }
        } catch (_: Exception) {
            // If the server request itself fails, the existing credential remains untouched.
            false
        }
    }

    fun forgetPairing(resetInstallationId: Boolean = false): Boolean {
        val cleared = credentialStore.clear()
        if (!cleared) return false
        if (resetInstallationId) {
            installationIdProvider.resetInstallationId()
        }
        return true
    }

    fun unpair(resetInstallationId: Boolean = false): Boolean = forgetPairing(resetInstallationId)
}
