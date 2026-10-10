package com.bulktext.gateway.cloud

import com.bulktext.gateway.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class SupabaseGatewayApi(
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val supabaseKey: String = BuildConfig.SUPABASE_ANON_KEY
) : GatewayApi, GatewayInventoryApi, GatewayQueueApi, GatewayDeliveryApi {

    @Throws(CloudException::class)
    override fun claimGatewayPairing(pairingCode: String, deviceName: String, installationId: String): ClaimPairingResult {
        val data = parseSingleRowResult(postRpc("claim_gateway_pairing", buildClaimPayload(pairingCode, deviceName, installationId)))
        return parseClaimResult(data)
    }

    @Throws(CloudException::class)
    override fun authenticateGatewayDevice(deviceId: String, credential: String): AuthDeviceResult {
        val data = parseSingleRowResult(postRpc("authenticate_gateway_device", buildAuthenticatePayload(deviceId, credential)))
        return parseAuthenticationResult(data, deviceId)
    }

    @Throws(CloudException::class)
    override fun rotateGatewayDeviceCredential(deviceId: String, currentCredential: String): RotateCredentialResult {
        val data = parseSingleRowResult(postRpc("rotate_gateway_device_credential", buildRotatePayload(deviceId, currentCredential)))
        return parseRotationResult(data, deviceId)
    }

    @Throws(CloudException::class)
    override fun reportGatewayDeviceInventory(deviceId: String, credential: String, inventory: DeviceInventorySnapshot): InventoryReportResult {
        val data = parseSingleRowResult(postRpc("report_gateway_device_inventory", buildInventoryPayload(deviceId, credential, inventory)))
        return parseInventoryResult(data, deviceId)
    }

    @Throws(CloudException::class)
    override fun claimGatewayMessageJobs(deviceId: String, credential: String, limit: Int): List<GatewayQueueJob> {
        val safeLimit = limit.coerceIn(1, 25)
        val payload = JSONObject().apply {
            put("p_device_id", deviceId)
            put("p_credential", credential)
            put("p_limit", safeLimit)
        }
        return parseQueueJobRows(postRpc("claim_gateway_message_jobs", payload))
    }

    @Throws(CloudException::class)
    override fun acknowledgeGatewayMessageJobs(deviceId: String, credential: String, receipts: List<QueueLeaseReceipt>): Int {
        require(receipts.size in 1..25) { "Queue ACK batch must contain between 1 and 25 jobs" }
        val payload = queueReceiptPayload(deviceId, credential, receipts)
        return parseIntegerResult(postRpc("acknowledge_gateway_message_jobs", payload), "Queue ACK response")
    }

    @Throws(CloudException::class)
    override fun releaseGatewayMessageJobLeases(deviceId: String, credential: String, receipts: List<QueueLeaseReceipt>): Int {
        require(receipts.size in 1..25) { "Queue release batch must contain between 1 and 25 jobs" }
        val payload = queueReceiptPayload(deviceId, credential, receipts)
        return parseIntegerResult(postRpc("release_gateway_message_job_leases", payload), "Queue release response")
    }


    @Throws(CloudException::class)
    override fun beginGatewayMessageAttempt(
        deviceId: String,
        credential: String,
        jobId: String,
        clientAttemptId: String,
        expectedParts: Int
    ): GatewayAttemptStart {
        require(expectedParts > 0) { "Attempt part count must be positive" }
        val payload = JSONObject().apply {
            put("p_device_id", deviceId)
            put("p_credential", credential)
            put("p_job_id", jobId)
            put("p_client_attempt_id", clientAttemptId)
            put("p_expected_parts", expectedParts)
        }
        return parseAttemptStart(parseSingleRowResult(postRpc("begin_gateway_message_attempt", payload)))
    }

    @Throws(CloudException::class)
    override fun reportGatewayMessageAttemptEvent(
        deviceId: String,
        credential: String,
        event: GatewayAttemptEvent
    ): GatewayAttemptReport {
        val payload = JSONObject().apply {
            put("p_device_id", deviceId)
            put("p_credential", credential)
            put("p_event_id", event.eventId)
            put("p_client_attempt_id", event.clientAttemptId)
            put("p_event_type", event.eventType)
            putOptional("p_part_index", event.partIndex)
            putOptional("p_result_code", event.resultCode)
            put("p_occurred_at_ms", event.occurredAtMillis)
        }
        return parseAttemptReport(parseSingleRowResult(postRpc("report_gateway_message_attempt_event", payload)))
    }

    @Throws(CloudException::class)
    override fun listGatewayMessageRecoveryRequests(deviceId: String, credential: String, limit: Int): List<GatewayRecoveryRequest> {
        val payload = JSONObject().apply {
            put("p_device_id", deviceId)
            put("p_credential", credential)
            put("p_limit", limit.coerceIn(1, 25))
        }
        return parseRecoveryRows(postRpc("list_gateway_message_recovery_requests", payload))
    }

    @Throws(CloudException::class)
    override fun acknowledgeGatewayMessageRecovery(deviceId: String, credential: String, recoveryId: String): Boolean {
        val payload = JSONObject().apply {
            put("p_device_id", deviceId)
            put("p_credential", credential)
            put("p_recovery_id", recoveryId)
        }
        val trimmed = postRpc("acknowledge_gateway_message_recovery", payload).trim().trim('"')
        if (trimmed != "true" && trimmed != "false") throw CloudException.ServerContractException("Recovery ACK response is not boolean")
        return trimmed == "true"
    }

    internal fun buildInventoryPayload(deviceId: String, credential: String, inventory: DeviceInventorySnapshot): JSONObject =
        JSONObject().apply {
            put("p_device_id", deviceId)
            put("p_credential", credential)
            put("p_device", JSONObject().apply {
                putOptional("manufacturer", inventory.manufacturer)
                putOptional("model", inventory.model)
                putOptional("androidRelease", inventory.androidRelease)
                putOptional("sdkInt", inventory.sdkInt)
                putOptional("appVersion", inventory.appVersion)
                putOptional("appVersionCode", inventory.appVersionCode)
                putOptional("batteryPercent", inventory.batteryPercent)
            })
            put("p_sims", JSONArray().apply {
                inventory.sims.forEach { sim ->
                    put(JSONObject().apply {
                        put("subscriptionId", sim.subscriptionId)
                        put("slotIndex", sim.slotIndex)
                        putOptional("carrierName", sim.carrierName)
                        putOptional("displayName", sim.displayName)
                        putOptional("countryIso", sim.countryIso)
                        put("isEmbedded", sim.isEmbedded)
                        putOptional("simIdentityHash", sim.simIdentityHash)
                    })
                }
            })
        }

    internal fun parseInventoryResult(data: JSONObject, requestedDeviceId: String): InventoryReportResult {
        val returnedDeviceId = requireString(data, "device_id", "Inventory response")
        if (returnedDeviceId != requestedDeviceId) throw CloudException.ServerContractException("Inventory response device identifier does not match the requested device")
        val status = BindingStatus.fromWire(requireString(data, "binding_status", "Inventory response"))
        val result = InventoryReportResult(
            deviceId = returnedDeviceId,
            organizationId = optionalString(data, "organization_id"),
            serverTime = requireTimestamp(data, "server_time", "Inventory response"),
            boundSimId = optionalString(data, "bound_sim_id"),
            boundSubscriptionId = optionalInt(data, "bound_subscription_id", "Inventory response"),
            boundSlotIndex = optionalInt(data, "bound_slot_index", "Inventory response"),
            boundCarrierName = optionalString(data, "bound_carrier_name"),
            bindingStatus = status
        )
        if (status == BindingStatus.READY && (result.boundSimId == null || result.boundSubscriptionId == null || result.boundSlotIndex == null)) {
            throw CloudException.ServerContractException("Ready inventory response is missing authoritative SIM binding fields")
        }
        return result
    }

    internal fun parseClaimResult(data: JSONObject): ClaimPairingResult = ClaimPairingResult(
        deviceId = requireString(data, "device_id", "Pairing response"),
        organizationId = optionalString(data, "organization_id"),
        deviceName = requireString(data, "device_name", "Pairing response"),
        credential = requireString(data, "credential", "Pairing response"),
        credentialVersion = requirePositiveInt(data, "credential_version", "Pairing response"),
        credentialExpiresAt = requireTimestamp(data, "credential_expires_at", "Pairing response"),
        pairedAt = requireTimestamp(data, "paired_at", "Pairing response")
    )

    internal fun parseAuthenticationResult(data: JSONObject, requestedDeviceId: String): AuthDeviceResult {
        val returnedDeviceId = requireString(data, "device_id", "Authentication response")
        if (returnedDeviceId != requestedDeviceId) throw CloudException.ServerContractException("Authentication response device identifier does not match the requested device")
        return AuthDeviceResult(
            deviceId = returnedDeviceId,
            organizationId = optionalString(data, "organization_id"),
            deviceName = requireString(data, "device_name", "Authentication response"),
            credentialVersion = requirePositiveInt(data, "credential_version", "Authentication response"),
            credentialExpiresAt = requireTimestamp(data, "credential_expires_at", "Authentication response"),
            serverTime = requireTimestamp(data, "server_time", "Authentication response")
        )
    }

    internal fun parseRotationResult(data: JSONObject, requestedDeviceId: String): RotateCredentialResult {
        val returnedDeviceId = requireString(data, "device_id", "Rotation response")
        if (returnedDeviceId != requestedDeviceId) throw CloudException.ServerContractException("Rotation response device identifier does not match the requested device")
        return RotateCredentialResult(
            deviceId = returnedDeviceId,
            credential = requireString(data, "credential", "Rotation response"),
            credentialVersion = requirePositiveInt(data, "credential_version", "Rotation response"),
            credentialExpiresAt = requireTimestamp(data, "credential_expires_at", "Rotation response")
        )
    }

    internal fun parseQueueJobRows(responseBody: String): List<GatewayQueueJob> {
        val rows = try { JSONArray(responseBody.trim()) } catch (_: Exception) {
            throw CloudException.ServerContractException("Queue claim response is not a JSON array")
        }
        if (rows.length() > 25) throw CloudException.ServerContractException("Queue claim returned more than the maximum batch size")
        return buildList {
            for (i in 0 until rows.length()) {
                val row = try { rows.getJSONObject(i) } catch (_: Exception) {
                    throw CloudException.ServerContractException("Queue claim contains a non-object row")
                }
                add(parseQueueJob(row))
            }
        }
    }

    internal fun parseQueueJob(data: JSONObject): GatewayQueueJob = try {
        GatewayQueueJob(
            jobId = requireUuidString(data, "job_id", "Queue job"),
            dispatchId = requireUuidString(data, "dispatch_id", "Queue job"),
            campaignId = requireUuidString(data, "campaign_id", "Queue job"),
            sourceRowNumber = requirePositiveInt(data, "source_row_number", "Queue job"),
            normalizedE164 = requireString(data, "normalized_e164", "Queue job"),
            renderedMessage = requireRawString(data, "rendered_message", "Queue job"),
            smsEncoding = requireString(data, "sms_encoding", "Queue job"),
            segmentCount = requirePositiveInt(data, "segment_count", "Queue job"),
            leaseToken = requireUuidString(data, "lease_token", "Queue job"),
            leaseVersion = requirePositiveInt(data, "lease_version", "Queue job"),
            leasedAt = requireTimestamp(data, "leased_at", "Queue job"),
            leaseExpiresAt = requireTimestamp(data, "lease_expires_at", "Queue job"),
            gatewaySimId = requireUuidString(data, "gateway_sim_id", "Queue job"),
            simSubscriptionId = requireNonNegativeInt(data, "sim_subscription_id", "Queue job"),
            simSlotIndex = requireNonNegativeInt(data, "sim_slot_index", "Queue job"),
            simIdentityHash = requireString(data, "sim_identity_hash", "Queue job").lowercase()
        )
    } catch (e: CloudException) {
        throw e
    } catch (_: IllegalArgumentException) {
        throw CloudException.ServerContractException("Queue claim contains an invalid job payload")
    }


    internal fun parseAttemptStart(data: JSONObject): GatewayAttemptStart = GatewayAttemptStart(
        attemptId = requireUuidString(data, "attempt_id", "Attempt start"),
        clientAttemptId = requireUuidString(data, "client_attempt_id", "Attempt start"),
        attemptNumber = requirePositiveInt(data, "attempt_number", "Attempt start"),
        state = requireString(data, "state", "Attempt start"),
        safeRetryEligible = data.optBoolean("safe_retry_eligible", false),
        createdAt = requireTimestamp(data, "created_at", "Attempt start")
    )

    internal fun parseAttemptReport(data: JSONObject): GatewayAttemptReport = GatewayAttemptReport(
        attemptId = requireUuidString(data, "attempt_id", "Attempt report"),
        clientAttemptId = requireUuidString(data, "client_attempt_id", "Attempt report"),
        attemptNumber = requirePositiveInt(data, "attempt_number", "Attempt report"),
        state = requireString(data, "state", "Attempt report"),
        safeRetryEligible = data.optBoolean("safe_retry_eligible", false),
        sentParts = requireNonNegativeInt(data, "sent_parts", "Attempt report"),
        failedSentParts = requireNonNegativeInt(data, "failed_sent_parts", "Attempt report"),
        deliveredParts = requireNonNegativeInt(data, "delivered_parts", "Attempt report")
    )

    internal fun parseRecoveryRows(responseBody: String): List<GatewayRecoveryRequest> {
        val rows = try { JSONArray(responseBody.trim()) } catch (_: Exception) {
            throw CloudException.ServerContractException("Recovery response is not a JSON array")
        }
        if (rows.length() > 25) throw CloudException.ServerContractException("Recovery response exceeded maximum batch size")
        return buildList {
            for (i in 0 until rows.length()) {
                val row = try { rows.getJSONObject(i) } catch (_: Exception) {
                    throw CloudException.ServerContractException("Recovery response contains a non-object row")
                }
                add(
                    GatewayRecoveryRequest(
                        recoveryId = requireUuidString(row, "recovery_id", "Recovery request"),
                        action = requireString(row, "action", "Recovery request"),
                        jobId = requireUuidString(row, "job_id", "Recovery request"),
                        campaignId = requireUuidString(row, "campaign_id", "Recovery request"),
                        failedClientAttemptId = requireUuidString(row, "failed_client_attempt_id", "Recovery request"),
                        attemptNumber = requirePositiveInt(row, "attempt_number", "Recovery request"),
                        requestedAt = requireTimestamp(row, "requested_at", "Recovery request")
                    )
                )
            }
        }
    }

    internal fun buildClaimPayload(pairingCode: String, deviceName: String, installationId: String): JSONObject = JSONObject().apply {
        put("p_pairing_code", pairingCode)
        put("p_device_name", deviceName)
        put("p_installation_id", installationId)
    }

    internal fun buildAuthenticatePayload(deviceId: String, credential: String): JSONObject = JSONObject().apply {
        put("p_device_id", deviceId)
        put("p_credential", credential)
    }

    internal fun buildRotatePayload(deviceId: String, currentCredential: String): JSONObject = JSONObject().apply {
        put("p_device_id", deviceId)
        put("p_current_credential", currentCredential)
    }

    internal fun queueReceiptPayload(deviceId: String, credential: String, receipts: List<QueueLeaseReceipt>): JSONObject = JSONObject().apply {
        put("p_device_id", deviceId)
        put("p_credential", credential)
        put("p_receipts", JSONArray().apply {
            receipts.forEach { r ->
                put(JSONObject().apply {
                    put("jobId", r.jobId)
                    put("leaseToken", r.leaseToken)
                    put("leaseVersion", r.leaseVersion)
                })
            }
        })
    }

    fun parseSingleRowResult(responseBody: String): JSONObject {
        val trimmed = responseBody.trim()
        if (!trimmed.startsWith("[")) throw CloudException.ServerContractException("Unexpected response format: expected a single-row JSON array")
        val rows = try { JSONArray(trimmed) } catch (_: Exception) { throw CloudException.ServerContractException("Invalid JSON array response") }
        if (rows.length() == 0) throw CloudException.ServerContractException("Empty response array from server")
        if (rows.length() != 1) throw CloudException.ServerContractException("Expected single row, received ${rows.length()} rows")
        return try { rows.getJSONObject(0) } catch (_: Exception) { throw CloudException.ServerContractException("Expected a JSON object in the single response row") }
    }

    fun parseIsoTimestamp(isoString: String): Long {
        if (isoString.isBlank()) throw CloudException.ServerContractException("Missing mandatory timestamp string")
        return try {
            OffsetDateTime.parse(isoString).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try { Instant.parse(isoString).toEpochMilli() } catch (_: Exception) {
                throw CloudException.ServerContractException("Invalid ISO-8601 timestamp format")
            }
        }
    }

    internal fun parseIntegerResult(responseBody: String, context: String): Int {
        val trimmed = responseBody.trim().trim('"')
        val value = trimmed.toIntOrNull() ?: throw CloudException.ServerContractException("$context is not an integer")
        if (value < 0 || value > 25) throw CloudException.ServerContractException("$context is outside the expected range")
        return value
    }

    private fun requireString(data: JSONObject, field: String, context: String): String {
        val value = if (data.has(field) && !data.isNull(field)) data.optString(field, "").trim() else ""
        if (value.isBlank()) throw CloudException.ServerContractException("$context missing mandatory field '$field'")
        return value
    }

    private fun requireRawString(data: JSONObject, field: String, context: String): String {
        if (!data.has(field) || data.isNull(field)) throw CloudException.ServerContractException("$context missing mandatory field '$field'")
        val value = data.optString(field, "")
        if (value.isEmpty()) throw CloudException.ServerContractException("$context missing mandatory field '$field'")
        return value
    }

    private fun requireUuidString(data: JSONObject, field: String, context: String): String {
        val value = requireString(data, field, context)
        try { UUID.fromString(value) } catch (_: Exception) { throw CloudException.ServerContractException("$context contains invalid field '$field'") }
        return value
    }

    private fun optionalString(data: JSONObject, field: String): String? =
        if (data.has(field) && !data.isNull(field)) data.optString(field, "").takeIf { it.isNotBlank() } else null

    private fun optionalInt(data: JSONObject, field: String, context: String): Int? {
        if (!data.has(field) || data.isNull(field)) return null
        return try { data.getInt(field) } catch (_: Exception) { throw CloudException.ServerContractException("$context contains invalid field '$field'") }
    }

    private fun requirePositiveInt(data: JSONObject, field: String, context: String): Int {
        if (!data.has(field) || data.isNull(field)) throw CloudException.ServerContractException("$context missing mandatory field '$field'")
        val value = try { data.getInt(field) } catch (_: Exception) { throw CloudException.ServerContractException("$context contains invalid field '$field'") }
        if (value < 1) throw CloudException.ServerContractException("$context contains invalid field '$field'")
        return value
    }

    private fun requireNonNegativeInt(data: JSONObject, field: String, context: String): Int {
        if (!data.has(field) || data.isNull(field)) throw CloudException.ServerContractException("$context missing mandatory field '$field'")
        val value = try { data.getInt(field) } catch (_: Exception) { throw CloudException.ServerContractException("$context contains invalid field '$field'") }
        if (value < 0) throw CloudException.ServerContractException("$context contains invalid field '$field'")
        return value
    }

    private fun requireTimestamp(data: JSONObject, field: String, context: String): Long = parseIsoTimestamp(requireString(data, field, context))

    private fun JSONObject.putOptional(name: String, value: Any?) { if (value != null) put(name, value) }

    private fun ensureConfigured() {
        val url = supabaseUrl.trim().trimEnd('/')
        if (!url.startsWith("https://") || url.contains("placeholder.supabase.co")) throw CloudException.ConfigurationException("BulkText cloud URL is not configured.")
        if (supabaseKey.isBlank() || supabaseKey == "placeholder-key") throw CloudException.ConfigurationException("BulkText cloud anon key is not configured.")
    }

    private fun postRpc(endpointName: String, body: JSONObject): String {
        ensureConfigured()
        val baseUrl = supabaseUrl.trim().trimEnd('/')
        val url = URL("$baseUrl/rest/v1/rpc/$endpointName")
        val conn = try {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("apikey", supabaseKey)
                setRequestProperty("Authorization", "Bearer $supabaseKey")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
            }
        } catch (e: IOException) {
            throw CloudException.NetworkException("Unable to open network connection", e)
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()); it.flush() }
        } catch (e: IOException) {
            conn.disconnect()
            throw CloudException.NetworkException("Network error while sending request", e)
        }

        val statusCode = try { conn.responseCode } catch (e: IOException) {
            conn.disconnect()
            throw CloudException.NetworkException("Network error while reading response", e)
        }
        val inputStream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
        val responseString = try {
            if (inputStream == null) "" else BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { it.readText() }
        } catch (e: IOException) {
            throw CloudException.NetworkException("Network error while reading response body", e)
        } finally {
            conn.disconnect()
        }
        if (statusCode !in 200..299) throw classifyApiError(endpointName, statusCode, responseString)
        if (responseString.isBlank()) throw CloudException.ServerContractException("Server returned an empty successful response")
        return responseString
    }

    internal fun classifyApiError(endpointName: String, statusCode: Int, responseBody: String): CloudException {
        val parsed = try { JSONObject(responseBody) } catch (_: Exception) { null }
        val message = parsed?.optString("message", "")?.trim().orEmpty()
        val code = parsed?.optString("code", "")?.trim().orEmpty()
        val lower = message.lowercase()

        if (endpointName == "claim_gateway_pairing") {
            if (lower.contains("already paired") || code == "23505") return CloudException.AlreadyPairedException()
            if ((lower.contains("pairing code") && (lower.contains("invalid") || lower.contains("expired") || lower.contains("not found"))) || code == "P0002") {
                return CloudException.InvalidPairingCodeException()
            }
        }

        val identityCredentialEndpoints = setOf(
            "authenticate_gateway_device",
            "rotate_gateway_device_credential",
            "report_gateway_device_inventory"
        )
        if (endpointName in identityCredentialEndpoints && (
            lower.contains("invalid gateway credential") ||
            lower.contains("credential revoked") ||
            lower.contains("credential expired") ||
            statusCode == 401 || statusCode == 403 || code == "42501"
        )) {
            return CloudException.CredentialRejectedException()
        }
        val queueEndpoints = setOf(
            "claim_gateway_message_jobs",
            "acknowledge_gateway_message_jobs",
            "release_gateway_message_job_leases",
            "begin_gateway_message_attempt",
            "report_gateway_message_attempt_event",
            "list_gateway_message_recovery_requests",
            "acknowledge_gateway_message_recovery"
        )
        if (endpointName in queueEndpoints && (
            lower.contains("invalid gateway credential") ||
            lower.contains("credential revoked") ||
            lower.contains("credential expired") ||
            statusCode == 401 || statusCode == 403 || code == "42501" ||
            lower.contains("authentication required") ||
            lower.contains("permission required")
        )) {
            return CloudException.CredentialRejectedException()
        }

        if (endpointName == "acknowledge_gateway_message_jobs" || endpointName == "release_gateway_message_job_leases") {
            if (code == "40001" || lower.contains("stale or mismatched queue lease")) {
                return CloudException.QueueLeaseConflictException()
            }
        }

        if (endpointName == "claim_gateway_message_jobs" && code == "22023" && (
            lower.contains("inventory is stale") ||
            lower.contains("sim binding changed") ||
            lower.contains("selected sim is missing") ||
            lower.contains("sim identity changed")
        )) {
            val safe = when {
                lower.contains("inventory is stale") -> "Gateway SIM inventory is stale. Refresh phone and SIM inventory before syncing jobs."
                lower.contains("binding changed") -> "Web-selected SIM binding changed. Cloud queue sync is blocked."
                lower.contains("missing") -> "Exact Web-selected SIM is missing. No fallback SIM is permitted."
                else -> "Exact Web-selected SIM identity changed. Cloud queue sync is blocked."
            }
            return CloudException.QueueBlockedException(safe)
        }

        return CloudException.HttpException(statusCode, "BulkText cloud request failed (HTTP $statusCode).")
    }
}
