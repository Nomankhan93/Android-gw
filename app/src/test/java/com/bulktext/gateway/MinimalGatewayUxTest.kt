package com.bulktext.gateway

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

class MinimalGatewayStatusTest {
    private fun label(enabled: Boolean = true, paired: Boolean = true, permitted: Boolean = true,
                      ready: Boolean = true, paused: Boolean = false, error: Boolean = false,
                      activated: Long = 1000, heartbeat: Long = 2000, now: Long = 3000) =
        MinimalGatewayStatus.label(enabled, paired, permitted, ready, paused, error, activated, heartbeat, now)
    @Test fun stoppedIsNotActive() { assertEquals("Stopped", label(enabled = false)) }
    @Test fun permissionsAndPairingNeedSetup() {
        assertEquals("Needs setup", label(permitted = false)); assertEquals("Needs setup", label(paired = false))
    }
    @Test fun missingBindingDoesNotClaimReady() { assertEquals("Waiting for selected SIM", label(ready = false)) }
    @Test fun enabledAloneDoesNotClaimActive() {
        assertEquals("Starting…", label(heartbeat = 0)); assertTrue(label(heartbeat = 0, now = 200000).startsWith("Needs attention"))
    }
    @Test fun staleOrPreviousActivationDoesNotClaimActive() {
        assertTrue(label(now = 200000).startsWith("Needs attention")); assertEquals("Starting…", label(activated = 2500))
        assertTrue(label(now = 1500).startsWith("Needs attention"))
        assertTrue(label(now = 200000, ready = false).startsWith("Needs attention"))
    }
    @Test fun unknownPauseAndOperationErrorsRemainVisible() {
        assertEquals("Sending paused — review in Web", label(paused = true)); assertEquals("Needs attention — sync will retry", label(error = true))
    }
    @Test fun freshHealthyWorkerShowsQualifiedActive() { assertEquals("Active — recent gateway activity", label()) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = GatewayApp::class, manifest = Config.NONE)
class MinimalGatewayScreenTest {
    private fun textViews(view: View): List<TextView> =
        (if (view is TextView) listOf(view) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) } else emptyList()
    private fun visible(view: View): Boolean = view.visibility == View.VISIBLE &&
        ((view.parent as? View)?.let { visible(it) } ?: true)
    @Test fun defaultScreenHidesDiagnosticsAndLabButExposesSetupAndStop() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as GatewayApp
        app.backgroundGatewayPreferences.disable(); app.identityRepository.forgetPairing(resetInstallationId = false)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get(); val views = textViews(activity.window.decorView)
            assertTrue(views.any { visible(it) && it.text.toString() == "Your SMS gateway" })
            assertTrue(views.any { visible(it) && it.text.toString() == "Grant phone & SMS permissions" })
            assertFalse(views.any { visible(it) && it.text.toString() == "Cloud SMS Execution" })
            assertFalse(views.any { visible(it) && it.text.toString() == "3. Authorized test recipients" })
            views.first { it.text.toString() == "Diagnostics & settings" }.performClick()
            assertTrue(views.any { visible(it) && it.text.toString() == "Cloud SMS Execution" })
            assertFalse(views.any { visible(it) && it.text.toString() == "3. Authorized test recipients" })
            app.backgroundGatewayPreferences.enable()
            val render = MainActivity::class.java.getDeclaredMethod("renderMinimalScreen").apply { isAccessible = true }
            render.invoke(activity)
            assertTrue(views.any { visible(it) && it.text.toString() == "Stop gateway" && it.isEnabled })
            views.first { it.text.toString() == "Stop gateway" }.performClick()
            assertFalse(app.backgroundGatewayPreferences.isEnabled())
        } finally { controller.destroy() }
    }
}
