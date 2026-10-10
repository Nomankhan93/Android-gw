package com.bulktext.gateway

import com.bulktext.gateway.cloud.*
import com.bulktext.gateway.identity.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GatewayIdentityTest {

    @Test
    fun pairingCodeNormalizationAndExactLength() {
        val api = FakeGatewayApi()
        val repo = GatewayIdentityRepository(FakeInstallationIdProvider(), FakeCredentialStore(), api)

        assertEquals("ABCD1234EFGH", repo.normalizePairingCode("  abcd-1234-EFGH  "))
        assertEquals("XYZ987654321", repo.normalizePairingCode("xyz 987 654 321"))

        val tooShort = repo.pair("ABC-123", "Phone")
        assertTrue(tooShort is GatewayIdentityState.Error)
        assertEquals("Pairing code must be 12 characters.", (tooShort as GatewayIdentityState.Error).message)
        assertEquals(0, api.claimCalls)
    }

    @Test
    fun secretModelsRedactCredentials() {
        val claim = ClaimPairingResult(
            deviceId = "dev-123",
            organizationId = "org-456",
            deviceName = "Test Device",
            credential = "super-secret-token-999",
            credentialVersion = 1,
            credentialExpiresAt = 1_900_000_000_000L,
            pairedAt = 1_800_000_000_000L
        )
        val rotate = RotateCredentialResult(
            deviceId = "dev-123",
            credential = "new-super-secret-token-888",
            credentialVersion = 2,
            credentialExpiresAt = 2_000_000_000_000L
        )

        assertFalse(claim.toString().contains("super-secret-token-999"))
        assertTrue(claim.toString().contains("[REDACTED]"))
        assertFalse(rotate.toString().contains("new-super-secret-token-888"))
        assertTrue(rotate.toString().contains("[REDACTED]"))
    }

    @Test
    fun rpcPayloadsUseExactPostgresArgumentNamesOnly() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")

        val claim = api.buildClaimPayload("ABCD1234EFGH", "Phone", "installation-123456")
        assertEquals(setOf("p_pairing_code", "p_device_name", "p_installation_id"), claim.keys().asSequence().toSet())

        val auth = api.buildAuthenticatePayload("dev-1", "credential")
        assertEquals(setOf("p_device_id", "p_credential"), auth.keys().asSequence().toSet())

        val rotate = api.buildRotatePayload("dev-1", "credential")
        assertEquals(setOf("p_device_id", "p_current_credential"), rotate.keys().asSequence().toSet())
    }

    @Test
    fun postgRestSingleRowParserIsStrict() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")
        val parsed = api.parseSingleRowResult("""[{"device_id":"dev-100"}]""")
        assertEquals("dev-100", parsed.getString("device_id"))

        assertThrows(CloudException.ServerContractException::class.java) { api.parseSingleRowResult("[]") }
        assertThrows(CloudException.ServerContractException::class.java) { api.parseSingleRowResult("[{}, {}]") }
        assertThrows(CloudException.ServerContractException::class.java) { api.parseSingleRowResult("{\"device_id\":\"dev\"}") }
        assertThrows(CloudException.ServerContractException::class.java) { api.parseSingleRowResult("[invalid-json") }
    }

    @Test
    fun iso8601TimestampParsingUsesServerValueOnly() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")
        assertTrue(api.parseIsoTimestamp("2026-12-30T12:30:00+00:00") > 0)
        assertTrue(api.parseIsoTimestamp("2026-12-30T12:30:00.123Z") > 0)
        assertThrows(CloudException.ServerContractException::class.java) { api.parseIsoTimestamp("") }
        assertThrows(CloudException.ServerContractException::class.java) { api.parseIsoTimestamp("invalid-date") }
    }

    @Test
    fun authenticationParserMatchesActualBackendReturnShape() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")
        val row = JSONObject(
            """{
              "device_id":"dev-1",
              "organization_id":"org-1",
              "device_name":"Vivo Y33s",
              "credential_version":3,
              "credential_expires_at":"2026-12-30T12:30:00Z",
              "server_time":"2026-10-01T15:00:00Z"
            }""".trimIndent()
        )
        val result = api.parseAuthenticationResult(row, "dev-1")
        assertEquals("dev-1", result.deviceId)
        assertEquals("Vivo Y33s", result.deviceName)
        assertEquals(3, result.credentialVersion)
        assertTrue(result.credentialExpiresAt > result.serverTime)

        assertThrows(CloudException.ServerContractException::class.java) {
            api.parseAuthenticationResult(row, "another-device")
        }
    }

    @Test
    fun apiErrorClassificationIsEndpointAware() {
        val api = SupabaseGatewayApi("https://example.supabase.co", "test-key")

        val pairError = api.classifyApiError(
            "claim_gateway_pairing",
            400,
            """{"code":"P0002","message":"Pairing code is invalid or expired"}"""
        )
        assertTrue(pairError is CloudException.InvalidPairingCodeException)

        val alreadyPaired = api.classifyApiError(
            "claim_gateway_pairing",
            409,
            """{"code":"23505","message":"This gateway installation is already paired. Unpair it before pairing again."}"""
        )
        assertTrue(alreadyPaired is CloudException.AlreadyPairedException)

        val authError = api.classifyApiError(
            "authenticate_gateway_device",
            403,
            """{"code":"42501","message":"Invalid gateway credential"}"""
        )
        assertTrue(authError is CloudException.CredentialRejectedException)
    }

    @Test
    fun successfulPairingPersistsReturnedCredential() {
        val api = FakeGatewayApi()
        val store = FakeCredentialStore()
        val repo = GatewayIdentityRepository(FakeInstallationIdProvider("installation-123456"), store, api)

        val state = repo.pair("ABCD1234EFGH", "My Phone")
        assertTrue(state is GatewayIdentityState.Paired)
        assertEquals("dev-id-1", store.getDeviceId())
        assertEquals("secret-credential-key-1", store.getCredential())
        assertEquals(1, api.claimCalls)
    }

    @Test
    fun authenticationUsesServerMetadataAndOfflineRetainsPairing() {
        val store = FakeCredentialStore().apply {
            saveCredential("dev-id-1", "secret-key", 1, System.currentTimeMillis() + 1_000_000L, "Old Name")
        }
        val api = FakeGatewayApi()
        val repo = GatewayIdentityRepository(FakeInstallationIdProvider(), store, api)

        val state = repo.authenticate()
        assertTrue(state is GatewayIdentityState.Paired)
        state as GatewayIdentityState.Paired
        assertEquals(2, state.credentialVersion)
        assertEquals("Server Device", state.deviceName)
        assertEquals(2, store.getCredentialVersion())

        api.networkError = true
        val offline = repo.authenticate()
        assertEquals(GatewayIdentityState.Offline, offline)
        assertEquals("secret-key", store.getCredential())
    }

    @Test
    fun rejectedCredentialMapsToBlockedState() {
        val store = FakeCredentialStore().apply {
            saveCredential("dev-id-1", "revoked-key", 1, System.currentTimeMillis() + 1_000_000L, "Phone")
        }
        val api = FakeGatewayApi().apply { credentialRejectedError = true }
        val repo = GatewayIdentityRepository(FakeInstallationIdProvider(), store, api)
        assertEquals(GatewayIdentityState.CredentialRejected, repo.authenticate())
    }

    @Test
    fun persistenceFailureDoesNotReportFalseSuccess() {
        val failedPairStore = FakeCredentialStore().apply { saveShouldFail = true }
        val repo = GatewayIdentityRepository(FakeInstallationIdProvider(), failedPairStore, FakeGatewayApi())
        val pairState = repo.pair("ABCD1234EFGH", "Phone")
        assertTrue(pairState is GatewayIdentityState.Error)

        val rotationStore = FakeCredentialStore().apply {
            saveCredential("dev-id-1", "old-key", 1, System.currentTimeMillis() + 1_000_000L, "Phone")
            saveShouldFail = true
        }
        val rotationRepo = GatewayIdentityRepository(FakeInstallationIdProvider(), rotationStore, FakeGatewayApi())
        assertFalse(rotationRepo.rotateCredential())
        // Server rotation may already have revoked the old secret, so a failed
        // replacement write clears local identity rather than keeping stale auth.
        assertNull(rotationStore.getDeviceId())
        assertNull(rotationStore.getCredential())
    }

    @Test
    fun forgetPairingRetainsInstallationIdentityAndSmsIdentityBoundary() {
        val store = FakeCredentialStore().apply {
            saveCredential("dev-1", "key1", 1, System.currentTimeMillis() + 1_000_000L, "Phone")
        }
        val installation = FakeInstallationIdProvider("fixed-installation-id")
        val repo = GatewayIdentityRepository(installation, store, FakeGatewayApi())

        assertTrue(repo.forgetPairing(resetInstallationId = false))
        assertNull(store.getDeviceId())
        assertNull(store.getCredential())
        assertEquals("fixed-installation-id", installation.getInstallationId())
    }

    private class FakeInstallationIdProvider(private var id: String = "fake-installation-id") : InstallationIdProvider {
        override fun getInstallationId(): String = id
        override fun resetInstallationId(): String {
            id = "reset-installation-id"
            return id
        }
    }

    private class FakeCredentialStore : CredentialStore {
        private var deviceId: String? = null
        private var credential: String? = null
        private var version: Int = 1
        private var expiresAt: Long = 0L
        private var deviceName: String = "Android Gateway"
        var saveShouldFail = false

        override fun saveCredential(deviceId: String, credential: String, version: Int, expiresAt: Long, deviceName: String): Boolean {
            if (saveShouldFail) return false
            this.deviceId = deviceId
            this.credential = credential
            this.version = version
            this.expiresAt = expiresAt
            this.deviceName = deviceName
            return true
        }

        override fun getDeviceId(): String? = deviceId
        override fun getDeviceName(): String = deviceName
        override fun getCredential(): String? = credential
        override fun getCredentialVersion(): Int = version
        override fun getExpiresAt(): Long = expiresAt
        override fun clear(): Boolean {
            deviceId = null
            credential = null
            version = 1
            expiresAt = 0L
            deviceName = "Android Gateway"
            return true
        }
    }

    private class FakeGatewayApi : GatewayApi {
        var claimCalls = 0
        var networkError = false
        var invalidPairingCodeError = false
        var credentialRejectedError = false

        override fun claimGatewayPairing(pairingCode: String, deviceName: String, installationId: String): ClaimPairingResult {
            claimCalls++
            if (networkError) throw CloudException.NetworkException("Network unreachable")
            if (invalidPairingCodeError) throw CloudException.InvalidPairingCodeException()
            return ClaimPairingResult(
                deviceId = "dev-id-1",
                organizationId = "org-1",
                deviceName = deviceName,
                credential = "secret-credential-key-1",
                credentialVersion = 1,
                credentialExpiresAt = System.currentTimeMillis() + 86_400_000L,
                pairedAt = System.currentTimeMillis()
            )
        }

        override fun authenticateGatewayDevice(deviceId: String, credential: String): AuthDeviceResult {
            if (networkError) throw CloudException.NetworkException("Network unreachable")
            if (credentialRejectedError) throw CloudException.CredentialRejectedException()
            val now = System.currentTimeMillis()
            return AuthDeviceResult(
                deviceId = deviceId,
                organizationId = "org-1",
                deviceName = "Server Device",
                credentialVersion = 2,
                credentialExpiresAt = now + 86_400_000L,
                serverTime = now
            )
        }

        override fun rotateGatewayDeviceCredential(deviceId: String, currentCredential: String): RotateCredentialResult {
            if (networkError) throw CloudException.NetworkException("Network unreachable")
            return RotateCredentialResult(
                deviceId = deviceId,
                credential = "new-rotated-key",
                credentialVersion = 2,
                credentialExpiresAt = System.currentTimeMillis() + 86_400_000L
            )
        }
    }
}
