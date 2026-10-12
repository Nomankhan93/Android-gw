package com.bulktext.gateway

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class WatchdogPass0186R1Test {
    @Test fun missingHeartbeatUsesActivationGrace() {
        assertFalse(GatewayWatchdogPolicy.needsRestart(true, 1000L, 0L, 120999L))
        assertTrue(GatewayWatchdogPolicy.needsRestart(true, 1000L, 0L, 121000L))
        assertFalse(GatewayWatchdogPolicy.needsRestart(false, 1000L, 0L, 999999L))
    }
    @Test fun heartbeatBeforeNewEnableDoesNotHideFailedStartup() {
        assertTrue(GatewayWatchdogPolicy.needsRestart(true, 5000L, 4000L, 125000L))
        assertFalse(GatewayWatchdogPolicy.needsRestart(true, 5000L, 5001L, 125000L))
    }
    @Test fun staleAndClockRollbackAreRecoverable() {
        assertFalse(GatewayWatchdogPolicy.needsRestart(true, 1L, 1000L, 900999L))
        assertTrue(GatewayWatchdogPolicy.needsRestart(true, 1L, 1000L, 901000L))
        assertTrue(GatewayWatchdogPolicy.needsRestart(true, 1L, 1000L, 500L))
        assertTrue(GatewayWatchdogPolicy.needsRestart(true, 0L, 0L, 1L))
    }
    @Test fun enabledPassFlushesThenRecoversThenRequestsRestart() {
        val calls = mutableListOf<String>()
        GatewayWatchdogPass({ true }, { calls += "callback" }, { calls += "recovery" }, { true }, { calls += "restart" }).run()
        assertEquals(listOf("callback", "recovery", "restart"), calls)
    }
    @Test fun disabledPassDoesNoWork() {
        GatewayWatchdogPass({ false }, { fail("callback") }, { fail("recovery") }, { fail("probe"); true }, { fail("restart") }).run()
    }
    @Test fun cancellationDuringCallbackPreventsRecoveryAndRestart() {
        var active = true
        GatewayWatchdogPass({ active }, { active = false }, { fail("recovery after stop") }, { true }, { fail("restart after stop") }).run()
    }
    @Test fun cancellationDuringRecoveryPreventsRestart() {
        var active = true
        GatewayWatchdogPass({ active }, {}, { active = false }, { true }, { fail("restart after stop") }).run()
    }
    @Test fun callbackFailureStillAllowsRecoveryAndRestart() {
        val calls = mutableListOf<String>()
        GatewayWatchdogPass({ true }, { throw IllegalStateException("offline") }, { calls += "recovery" }, { true }, { calls += "restart" }).run()
        assertEquals(listOf("recovery", "restart"), calls)
    }
    @Test fun recoveryFailureDoesNotHideRestartNeed() {
        var restarted = false
        GatewayWatchdogPass({ true }, {}, { throw IllegalStateException("offline") }, { true }, { restarted = true }).run()
        assertTrue(restarted)
    }
    @Test fun healthyLoopDoesNotRestart() {
        GatewayWatchdogPass({ true }, {}, {}, { false }, { fail("unnecessary restart") }).run()
    }
    @Test fun disableThenReenableInvalidatesOldRun() {
        var enabled = true; var generation = 1L
        val gate = CloudSubmissionGate({ generation }, { enabled })
        val permit = gate.capture(true)!!
        GatewayWatchdogPass({ gate.isAllowed(permit) }, {
            gate.changeAuthorization { enabled = false; generation++ }
            gate.changeAuthorization { enabled = true; generation++ }
        }, { fail("old recovery") }, { true }, { fail("old restart") }).run()
        assertTrue(enabled)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=GatewayApp::class, manifest=Config.NONE)
class WatchdogScheduler0186R1Test {
    private val app get() = RuntimeEnvironment.getApplication() as GatewayApp
    private val prefs get() = app.backgroundGatewayPreferences
    private val scheduler get() = requireNotNull(app.getSystemService(JobScheduler::class.java))

    @Test fun repeatedScheduleKeepsSamePeriodicJob() {
        prefs.enable()
        assertTrue(GatewayWatchdogService.schedule(app))
        val first = scheduler.getPendingJob(GatewayWatchdogService.JOB_ID)!!
        repeat(3) { assertTrue(GatewayWatchdogService.schedule(app)) }
        assertSame(first, scheduler.getPendingJob(GatewayWatchdogService.JOB_ID))
        assertTrue(first.isPersisted)
        assertTrue(first.isPeriodic)
        assertEquals(GatewayWatchdogPolicy.INTERVAL_MS, first.intervalMillis)
    }
    @Test fun incompatibleJobIsReplaced() {
        prefs.enable()
        scheduler.schedule(JobInfo.Builder(GatewayWatchdogService.JOB_ID, ComponentName(app, GatewayWatchdogService::class.java)).setMinimumLatency(1L).build())
        val old = scheduler.getPendingJob(GatewayWatchdogService.JOB_ID)
        assertTrue(GatewayWatchdogService.schedule(app))
        assertNotSame(old, scheduler.getPendingJob(GatewayWatchdogService.JOB_ID))
        assertTrue(scheduler.getPendingJob(GatewayWatchdogService.JOB_ID)!!.isPeriodic)
    }
    @Test fun disableCancelsWatchdogAndInvalidatesPermit() {
        prefs.enable(); GatewayWatchdogService.schedule(app)
        val permit = prefs.submissionGate.capture(true)!!
        prefs.disable()
        assertNull(scheduler.getPendingJob(GatewayWatchdogService.JOB_ID))
        assertFalse(prefs.submissionGate.isAllowed(permit))
        assertFalse(GatewayWatchdogService.schedule(app))
    }
    @Test fun disabledBootAndUpgradeDoNotStartGateway() {
        prefs.disable()
        val receiver = GatewayBootReceiver()
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNull(scheduler.getPendingJob(GatewayWatchdogService.JOB_ID))
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(prefs.isEnabled())
    }
    @Test fun enabledBootAndUpgradeKeepActivationAndSchedule() {
        prefs.enable(1000L)
        val permit = prefs.submissionGate.capture(true)!!
        val receiver = GatewayBootReceiver()
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val first = scheduler.getPendingJob(GatewayWatchdogService.JOB_ID)
        assertNotNull(first)
        assertEquals(BackgroundGatewayService::class.java.name, requireNotNull(shadowOf(app).nextStartedService).component!!.className)
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertSame(first, scheduler.getPendingJob(GatewayWatchdogService.JOB_ID))
        assertEquals(1000L, prefs.activatedAt())
        assertTrue(prefs.submissionGate.isAllowed(permit))
    }
}
