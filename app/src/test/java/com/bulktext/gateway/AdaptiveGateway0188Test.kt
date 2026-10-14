package com.bulktext.gateway

import com.bulktext.gateway.cloud.CloudException
import com.bulktext.gateway.cloud.GatewayRequestCoordinator
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AdaptiveGateway0188Test {
    @Test fun idleDelayIsBoundedAndWorkRestoresResponsiveness() {
        val policy = AdaptiveGatewayPolling()
        assertEquals(listOf(15000L, 30000L, 60000L, 60000L), (1..4).map { policy.nextDelay(false) })
        assertEquals(15000L, policy.nextDelay(true))
        assertEquals(15000L, policy.nextDelay(false))
    }
    @Test fun cooldownDoesNotReplayAndSuccessResetsFailures() {
        var now = 0L; var calls = 0
        val coordinator = GatewayRequestCoordinator({ now }, { 1.0 })
        fun runFailure() {
            try { coordinator.execute("device", "claim") { calls++; throw CloudException.NetworkException("offline") }; fail("expected failure") }
            catch (_: CloudException) { }
        }
        runFailure(); runFailure(); assertEquals(1, calls)
        now = 15000; runFailure(); assertEquals(2, calls)
        now = 44999; runFailure(); assertEquals(2, calls)
        now = 45000
        assertEquals("ok", coordinator.execute("device", "claim") { "ok" })
        runFailure(); now += 15000; runFailure(); assertEquals(4, calls)
    }
    @Test fun retryAfterCannotBeBypassedByAnotherCaller() {
        var now = 0L; var calls = 0
        val coordinator = GatewayRequestCoordinator({ now }, { 1.0 })
        try { coordinator.execute("d", "recovery") { calls++; throw CloudException.HttpException(429, "busy", 120000) } } catch (_: CloudException) { }
        now = 119999
        try { coordinator.execute("d", "recovery") { calls++ }; fail("expected cooldown") } catch (_: CloudException.HttpException) { }
        assertEquals(1, calls)
        now = 120000; coordinator.execute("d", "recovery") { calls++ }; assertEquals(2, calls)
    }
    @Test fun revokedCredentialStopsNetworkButNewIdentityCanProceed() {
        val coordinator = GatewayRequestCoordinator(); var calls = 0
        try { coordinator.execute("old", "inventory") { calls++; throw CloudException.CredentialRejectedException() } } catch (_: CloudException) { }
        try { coordinator.execute("old", "claim") { calls++ }; fail("expected rejection") } catch (_: CloudException.CredentialRejectedException) { }
        coordinator.execute("new", "claim") { calls++ }; assertEquals(2, calls)
    }
    @Test fun domainFailureDoesNotCooldownUnrelatedWork() {
        val coordinator = GatewayRequestCoordinator()
        try { coordinator.execute("d", "claim") { throw CloudException.QueueBlockedException("SIM changed") } } catch (_: CloudException) { }
        assertEquals(1, coordinator.execute("d", "claim") { 1 })
    }
    @Test fun transportHasOnlyOneInFlightRequest() {
        val coordinator = GatewayRequestCoordinator()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val first = Thread { coordinator.execute("a", "claim") { calls.incrementAndGet(); entered.countDown(); release.await(2, TimeUnit.SECONDS) } }
        val second = Thread { coordinator.execute("a", "inventory") { calls.incrementAndGet() } }
        first.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); second.start()
        assertEquals(1, calls.get()); release.countDown()
        first.join(2000); second.join(2000)
        assertFalse(first.isAlive); assertFalse(second.isAlive); assertEquals(2, calls.get())
    }
    @Test fun parsesRetryAfterWithoutOverflow() {
        assertEquals(60000L, GatewayRequestCoordinator.retryAfter("60"))
        assertEquals(60000L, GatewayRequestCoordinator.retryAfter("Wed, 01 Jan 2025 00:01:00 GMT", 1735689600000))
        assertEquals(86400000L, GatewayRequestCoordinator.retryAfter(Long.MAX_VALUE.toString()))
        assertEquals(0L, GatewayRequestCoordinator.retryAfter("invalid"))
    }
}
