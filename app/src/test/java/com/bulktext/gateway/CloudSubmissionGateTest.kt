package com.bulktext.gateway

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CloudSubmissionGateTest {
    @Test fun disableDuringNetworkPreparationPreventsSubmission() {
        var generation = 1L
        var enabled = true
        val gate = CloudSubmissionGate({ generation }, { enabled })
        val permit = gate.capture(true)!!
        gate.changeAuthorization { enabled = false; generation++ }
        var submissions = 0
        assertFalse(gate.runIfAllowed(permit) { submissions++ })
        assertEquals(0, submissions)
    }

    @Test fun rapidReenableDoesNotReviveOldAutomaticOrManualBatch() {
        var generation = 1L
        var enabled = true
        val gate = CloudSubmissionGate({ generation }, { enabled })
        val automatic = gate.capture(true)!!
        val manual = gate.capture(false)!!
        gate.changeAuthorization { enabled = false; generation++ }
        gate.changeAuthorization { enabled = true; generation++ }
        assertFalse(gate.isAllowed(automatic))
        assertFalse(gate.isAllowed(manual))
        assertTrue(gate.isAllowed(gate.capture(true)!!))
    }

    @Test fun manualConfirmationStillWorksWithAutomaticModeOff() {
        val gate = CloudSubmissionGate({ 9L }, { false })
        assertNull(gate.capture(true))
        var sent = false
        assertTrue(gate.runIfAllowed(gate.capture(false)!!) { sent = true })
        assertTrue(sent)
    }

    @Test fun disableSerializesWithAnInFlightBoundaryAndBlocksTheNextOne() {
        var generation = 1L
        var enabled = true
        val gate = CloudSubmissionGate({ generation }, { enabled })
        val permit = gate.capture(true)!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disableStarted = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val sent = AtomicInteger()
        val sender = Thread {
            gate.runIfAllowed(permit) {
                sent.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        sender.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val stopper = Thread {
            disableStarted.countDown()
            gate.changeAuthorization { enabled = false; generation++ }
            stopped.countDown()
        }
        stopper.start()
        try {
            assertTrue(disableStarted.await(5, TimeUnit.SECONDS))
            assertFalse(stopped.await(100, TimeUnit.MILLISECONDS))
        } finally { release.countDown() }
        assertTrue(stopped.await(5, TimeUnit.SECONDS))
        sender.join(5_000L); stopper.join(5_000L)
        assertFalse(gate.runIfAllowed(permit) { sent.incrementAndGet() })
        assertEquals(1, sent.get())
    }

    @Test fun submissionFailureDoesNotHoldTheStopLock() {
        var generation = 1L
        val gate = CloudSubmissionGate({ generation }, { true })
        val permit = gate.capture(true)!!
        assertThrows(IllegalStateException::class.java) {
            gate.runIfAllowed(permit) { error("Simulated external boundary failure") }
        }
        gate.changeAuthorization { generation++ }
        assertFalse(gate.isAllowed(permit))
    }
}
