package com.bulktext.gateway.cloud

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Serializes actual transport, applies cooldown without replaying a request. */
class GatewayRequestCoordinator(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val jitter: () -> Double = { Math.random() }
) {
    private data class Failure(val count: Int, val until: Long)
    private val failures = mutableMapOf<Pair<String, String>, Failure>()
    private val rejected = mutableSetOf<String>()

    @Synchronized
    fun <T> execute(identity: String, operation: String, request: () -> T): T {
        if (identity in rejected) throw CloudException.CredentialRejectedException()
        val key = identity to operation
        val previous = failures[key]
        if (previous != null && now() < previous.until)
            throw CloudException.HttpException(429, "Gateway sync cooling down; durable work retained.", previous.until - now())
        try {
            val result = request()
            failures.remove(key)
            return result
        } catch (e: CloudException.CredentialRejectedException) {
            rejected.add(identity)
            throw e
        } catch (e: CloudException) {
            val transient = e is CloudException.NetworkException ||
                (e is CloudException.HttpException && (e.statusCode == 429 || e.statusCode >= 500))
            if (transient) {
                val count = ((previous?.count ?: 0) + 1).coerceAtMost(6)
                val base = (15_000L * (1L shl (count - 1))).coerceAtMost(300_000L)
                val delay = (base * (0.8 + jitter().coerceIn(0.0, 1.0) * 0.2)).toLong()
                val server = (e as? CloudException.HttpException)?.retryAfterMs ?: 0L
                failures[key] = Failure(count, now() + maxOf(delay, server))
            }
            throw e
        }
    }

    companion object {
        fun retryAfter(value: String?, wallTime: Long = System.currentTimeMillis()): Long {
            if (value == null) return 0L
            val seconds = value.trim().toLongOrNull()
            if (seconds != null) return seconds.coerceIn(0L, 86_400L) * 1000L
            return try { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - wallTime).coerceIn(0L, 86_400_000L) }
            catch (_: Exception) { 0L }
        }
    }
}
