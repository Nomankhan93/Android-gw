package com.bulktext.gateway

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.telephony.SubscriptionInfo
import android.text.InputType
import android.view.View
import android.widget.*
import com.bulktext.gateway.identity.GatewayIdentityState
import com.bulktext.gateway.inventory.GatewayInventoryState
import com.bulktext.gateway.queue.CloudQueueExecutionIdentity
import com.bulktext.gateway.queue.CloudQueueState
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val app get() = application as GatewayApp
    private val store get() = app.store
    private val identityRepo get() = app.identityRepository
    private val inventoryRepo get() = app.inventoryRepository
    private val inventoryProvider get() = app.inventoryProvider
    private val cloudQueueRepo get() = app.cloudQueueRepository

    private lateinit var layout: LinearLayout
    private lateinit var pairingStatus: TextView
    private lateinit var pairingCodeInput: EditText
    private lateinit var pairBtn: Button
    private lateinit var unpairBtn: Button
    private lateinit var cloudSimStatus: TextView
    private lateinit var cloudQueueStatus: TextView
    private lateinit var cloudExecutionStatus: TextView
    private lateinit var cloudCallbackDiagnosticsStatus: TextView
    private lateinit var backgroundGatewayStatus: TextView
    private lateinit var backgroundGatewayButton: Button
    private lateinit var manualCampaignButton: Button

    private lateinit var sim: Spinner
    private lateinit var numbers: EditText
    private lateinit var body: EditText
    private lateinit var consent: CheckBox
    private lateinit var status: TextView
    private lateinit var permissions: TextView

    private val sendingIntent by lazy { Intent(this, SendingService::class.java) }
    private var sims = emptyList<SubscriptionInfo>()
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable { override fun run() { render(); handler.postDelayed(this, 1000) } }
    private val autoSync = object : Runnable {
        override fun run() {
            if (!activityForeground) return
            maybeAutoSyncCloudQueue()
            if (activityForeground) handler.postDelayed(this, 30_000)
        }
    }

    private var currentState: GatewayIdentityState = GatewayIdentityState.Unpaired
    private var inventoryState: GatewayInventoryState = GatewayInventoryState.NotReported
    private var inventoryReportInFlight = false
    private var cloudQueueSyncInFlight = false
    private var cloudQueueState: CloudQueueState = CloudQueueState.NotSynced
    private var activityForeground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
            setBackgroundColor(Color.rgb(243, 247, 249))
        }
        scroll.addView(layout)
        setContentView(scroll)

        scroll.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }

        text("BULKTEXT  /  ANDROID LAB", 14).setTextColor(Color.rgb(8, 127, 140))
        text("Test your SIM gateway", 28)
        text("0.18.3 • Persistent Background Sync + Callback Diagnostics\nEnable the background gateway once. Queue/callback/recovery sync remains active through UNKNOWN, raw callback resultCode/errorCode evidence is retained per part, and UNKNOWN is never automatically resent. Exact-SIM checks remain mandatory before every SMS.", 15)

        // --- Gateway Cloud Pairing Section ---
        text("Cloud Gateway Identity", 20)
        pairingStatus = text("Checking pairing state…", 14)
        pairingCodeInput = edit("Enter 12-character pairing code", 1).apply {
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_CLASS_TEXT
        }

        val btnLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pairBtn = Button(this).apply {
            text = "Pair phone"
            isAllCaps = false
            setOnClickListener { doPair() }
        }
        unpairBtn = Button(this).apply {
            text = "Forget this pairing on this phone"
            isAllCaps = false
            setOnClickListener { doUnpair() }
        }
        btnLayout.addView(pairBtn)
        btnLayout.addView(unpairBtn)
        layout.addView(btnLayout)

        // --- Cloud SIM Inventory & Explicit Binding ---
        text("Cloud SIM binding", 20)
        cloudSimStatus = text("Cloud SIM status: Not reported yet", 14)
        button("Refresh phone & SIM inventory") { refreshCloudInventory() }
        text("BulkText Web chooses the authoritative SIM. This phone reports active SIMs and the server returns the selected binding. If that SIM is missing, BulkText shows Missing and never substitutes another SIM. The local SMS lab below remains a manual transport test.", 14)

        // --- Background Gateway + Recovery (0.18) ---
        text("Background Gateway", 20)
        backgroundGatewayStatus = text("Background gateway: Disabled", 14)
        backgroundGatewayButton = button("Enable background gateway") { toggleBackgroundGateway() }
        text("Enable once before sending from Web. The persistent gateway syncs jobs, records callback truth, and polls explicit Web recovery while paused. Existing pre-activation READY jobs are not silently auto-sent. UNKNOWN is never automatically retried.", 14)

        // --- Durable Cloud Queue Client (sync remains non-sending) ---
        text("Cloud Queue", 20)
        cloudQueueStatus = text("Cloud Queue status: Not synced yet", 14)
        button("Sync cloud jobs (manual / recovery)") { syncCloudQueue() }
        text("0.18 background gateway handles queue sync and callback/recovery sync while active. Manual queue sync remains diagnostics-only; it never sends SMS by itself.", 14)

        // --- Cloud SMS Execution (0.18 callbacks + explicit recovery) ---
        text("Cloud SMS Execution", 20)
        cloudExecutionStatus = text("Cloud execution: No campaign submitted yet", 14)
        cloudCallbackDiagnosticsStatus = text("Callback diagnostics: No callbacks recorded yet", 13)
        manualCampaignButton = button("Send campaign (manual / recovery)") { previewCloudCampaignSend() }
        text("0.18 normally executes through the background gateway. Every attempt is registered before SmsManager; complete SENT callbacks establish SENT, DELIVERED remains separate, and callback failure after SmsManager is UNKNOWN rather than retry-safe. UNKNOWN pauses SMS execution while queue/callback/recovery sync stays active until Web chooses continue-without-resend.", 14)

        // --- SMS Local Lab Section ---
        permissions = text("", 14)
        button("1. Grant permissions / refresh SIMs") { grant() }
        text("2. Select the sending SIM", 18)
        sim = Spinner(this)
        layout.addView(sim)
        text("The chosen SIM sends the messages. Carrier bundle balance is not available here.", 14)

        text("3. Authorized test recipients", 18)
        numbers = edit("One +country number per line; Pakistan 03… accepted", 4)
        numbers.id = R.id.recipients

        text("4. Message", 18)
        body = edit("Type an English or Urdu test message", 5)
        body.id = R.id.message

        consent = CheckBox(this).apply { setText(R.string.consent) }
        layout.addView(consent)

        button("Preview & confirm test") { preview() }
        button("Resume queued messages only") {
            val s = store.latest() ?: error("No session")
            require(s.status in listOf("READY", "PAUSED")) { "Session is ${s.status}" }
            Telephony.check(this, s)
            AlertDialog.Builder(this).setTitle("Resume queued messages?")
                .setMessage("Only messages never submitted will be sent. Unknown/failed/partial messages are NOT retried. Selected subscription: ${s.sub}.")
                .setNegativeButton("Back", null).setPositiveButton("Resume") { _, _ -> guard { launch(s.id) } }.show()
        }
        button("Pause session") {
            store.latest()?.let { store.stop(it.id, "PAUSED") }
            stopService(sendingIntent)
            render()
        }
        button("Cancel remaining messages") {
            store.latest()?.let { store.stop(it.id, "CANCELLED") }
            stopService(sendingIntent)
            render()
        }

        text("Session & results", 20)
        status = text("", 15)

        button("Export diagnostic JSON (numbers/body excluded)") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "bulktext-diagnostics.json")
            }, 20)
        }

        button("Delete local test history") {
            require(store.latest()?.status != "RUNNING") { "Pause before deleting" }
            AlertDialog.Builder(this).setTitle("Delete local history?")
                .setMessage("Export diagnostics first if needed. Deleting history does not recall any SMS.")
                .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ -> guard { store.erase(); render() } }.show()
        }

        text("Sent = Android send callback succeeded. Delivered appears only when a delivery callback is received. COMPLETE means all jobs were submitted, not that every SMS was delivered.\n\nRestart/force-stop never auto-resends. Unknown or partially sent messages need manual review before creating another test.", 14)

        refreshSims()
        checkIdentityState()
    }

    private fun checkIdentityState() {
        currentState = identityRepo.getCurrentState()
        renderIdentity()
        if (currentState is GatewayIdentityState.Paired) {
            executor.execute {
                val state = identityRepo.authenticate()
                handler.post {
                    currentState = state
                    renderIdentity()
                    if (state is GatewayIdentityState.Paired) {
                        refreshCloudInventory()
                        handler.postDelayed({ maybeAutoSyncCloudQueue() }, 1000)
                    }
                }
            }
        }
    }

    private fun doPair() {
        val code = pairingCodeInput.text.toString()
        if (code.isBlank()) {
            Toast.makeText(this, "Enter pairing code", Toast.LENGTH_SHORT).show()
            return
        }
        currentState = GatewayIdentityState.Pairing
        renderIdentity()

        executor.execute {
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val resultState = identityRepo.pair(code, deviceName)
            handler.post {
                currentState = resultState
                if (resultState is GatewayIdentityState.Paired) {
                    pairingCodeInput.setText("")
                    Toast.makeText(this, "Paired successfully", Toast.LENGTH_SHORT).show()
                }
                renderIdentity()
                if (resultState is GatewayIdentityState.Paired) refreshCloudInventory()
            }
        }
    }

    private fun doUnpair() {
        AlertDialog.Builder(this)
            .setTitle("Forget local pairing?")
            .setMessage("Local gateway credentials will be removed from this phone.\n\nNote: If required, cloud device revocation can be performed in the BulkText web application. Local SMS logs on this phone will be retained.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Forget pairing") { _, _ ->
                val cleared = identityRepo.forgetPairing(resetInstallationId = false)
                if (cleared) {
                    currentState = GatewayIdentityState.Unpaired
                    inventoryState = GatewayInventoryState.Unpaired
                    cloudQueueState = CloudQueueState.NotSynced
                    renderIdentity()
                    renderCloudInventory()
                    renderCloudQueue()
                    Toast.makeText(this, "Local pairing forgotten", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Unable to clear local pairing. Try again.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun refreshCloudInventory() {
        if (!::cloudSimStatus.isInitialized || inventoryReportInFlight) return
        if (currentState !is GatewayIdentityState.Paired) {
            inventoryState = GatewayInventoryState.Unpaired
            renderCloudInventory()
            return
        }
        if (!inventoryProvider.canReadSims()) {
            inventoryState = GatewayInventoryState.PermissionRequired
            renderCloudInventory()
            return
        }

        inventoryReportInFlight = true
        inventoryState = GatewayInventoryState.Reporting
        renderCloudInventory()
        executor.execute {
            val result = try {
                inventoryRepo.report(inventoryProvider.snapshot())
            } catch (e: Exception) {
                GatewayInventoryState.Error(e.message ?: "Unable to read phone and SIM inventory.")
            }
            handler.post {
                inventoryReportInFlight = false
                inventoryState = result
                if (result is GatewayInventoryState.CredentialRejected) {
                    currentState = GatewayIdentityState.CredentialRejected
                    renderIdentity()
                }
                renderCloudInventory()
            }
        }
    }

    private fun renderCloudInventory() {
        if (!::cloudSimStatus.isInitialized) return
        when (val state = inventoryState) {
            GatewayInventoryState.NotReported -> {
                cloudSimStatus.text = "Cloud SIM status: Not reported yet"
                cloudSimStatus.setTextColor(Color.rgb(100, 110, 120))
            }
            GatewayInventoryState.Reporting -> {
                cloudSimStatus.text = "Cloud SIM status: Reporting phone and active SIMs…"
                cloudSimStatus.setTextColor(Color.rgb(200, 120, 0))
            }
            GatewayInventoryState.Unpaired -> {
                cloudSimStatus.text = "Cloud SIM status: Pair this phone first"
                cloudSimStatus.setTextColor(Color.rgb(100, 110, 120))
            }
            GatewayInventoryState.PermissionRequired -> {
                cloudSimStatus.text = "Cloud SIM status: Phone permission required to read active SIMs"
                cloudSimStatus.setTextColor(Color.rgb(180, 100, 0))
            }
            GatewayInventoryState.Offline -> {
                cloudSimStatus.text = "Cloud SIM status: Offline — no binding change assumed"
                cloudSimStatus.setTextColor(Color.rgb(180, 100, 0))
            }
            GatewayInventoryState.CredentialRejected -> {
                cloudSimStatus.text = "Cloud SIM status: Gateway credential rejected — re-pair required"
                cloudSimStatus.setTextColor(Color.RED)
            }
            is GatewayInventoryState.Unbound -> {
                cloudSimStatus.text = "Cloud SIM status: No SIM selected in BulkText Web"
                cloudSimStatus.setTextColor(Color.rgb(180, 100, 0))
            }
            is GatewayInventoryState.Ready -> {
                val b = state.binding
                cloudSimStatus.text = "Cloud SIM status: Ready\nWeb-selected SIM ${b.slotIndex + 1} • ${b.carrierName ?: "carrier unavailable"} • subscription ${b.subscriptionId}"
                cloudSimStatus.setTextColor(Color.rgb(0, 128, 0))
            }
            is GatewayInventoryState.Missing -> {
                val b = state.binding
                cloudSimStatus.text = if (b == null) {
                    "Cloud SIM status: Selected SIM is missing. Sending must stay blocked until Web selection is valid again."
                } else {
                    "Cloud SIM status: Missing\nSelected SIM ${b.slotIndex + 1} • ${b.carrierName ?: "carrier unavailable"} • subscription ${b.subscriptionId} is not active. No fallback SIM will be used."
                }
                cloudSimStatus.setTextColor(Color.RED)
            }
            is GatewayInventoryState.Error -> {
                cloudSimStatus.text = "Cloud SIM status: Error — ${state.message}"
                cloudSimStatus.setTextColor(Color.RED)
            }
        }
    }

    private fun maybeAutoSyncCloudQueue() {
        if (app.backgroundGatewayPreferences.isEnabled()) return
        if (cloudQueueSyncInFlight) return
        val paired = currentState as? GatewayIdentityState.Paired ?: return
        if (!inventoryProvider.canReadSims()) return
        val counts = app.cloudQueueStore.executionCounts(paired.deviceId)
        if (counts.submitting > 0 || counts.unknown > 0) return
        syncCloudQueue()
    }

    private fun syncCloudQueue() {
        if (!::cloudQueueStatus.isInitialized || cloudQueueSyncInFlight) return
        val paired = currentState as? GatewayIdentityState.Paired
        if (paired == null) {
            cloudQueueState = CloudQueueState.Error("Pair and authenticate this phone first.", cloudQueueRepo.countsForCurrentDevice())
            renderCloudQueue()
            return
        }
        if (!inventoryProvider.canReadSims()) {
            inventoryState = GatewayInventoryState.PermissionRequired
            cloudQueueState = CloudQueueState.SimBlocked("Phone permission is required to refresh exact SIM inventory.", cloudQueueRepo.countsForCurrentDevice())
            renderCloudInventory()
            renderCloudQueue()
            return
        }

        cloudQueueSyncInFlight = true
        cloudQueueState = CloudQueueState.Syncing
        renderCloudQueue()

        executor.execute {
            // 0.18.3: manual diagnostics sync also services callback/recovery; background service makes it optional
            // control traffic. Recovery never sends SMS by itself.
            var recoveredThisSync = 0
            try { app.cloudDeliveryRepository.flushEvents() } catch (_: Exception) { }
            try { recoveredThisSync += app.cloudDeliveryRepository.syncRecoveryRequests() } catch (_: Exception) { }

            val snapshot = try {
                inventoryProvider.snapshot()
            } catch (e: Exception) {
                handler.post {
                    cloudQueueSyncInFlight = false
                    cloudQueueState = CloudQueueState.Error(e.message ?: "Unable to read current SIM inventory.", cloudQueueRepo.countsForCurrentDevice())
                    renderCloudQueue()
                }
                return@execute
            }

            val refreshedInventory = try {
                inventoryRepo.report(snapshot)
            } catch (e: Exception) {
                GatewayInventoryState.Error(e.message ?: "Unable to refresh current SIM inventory.")
            }

            val queueResult: CloudQueueState = when (refreshedInventory) {
                is GatewayInventoryState.Ready -> {
                    val binding = refreshedInventory.binding
                    val exactLocalSim = snapshot.sims.firstOrNull {
                        it.subscriptionId == binding.subscriptionId &&
                            it.slotIndex == binding.slotIndex &&
                            !it.simIdentityHash.isNullOrBlank()
                    }
                    if (exactLocalSim == null) {
                        CloudQueueState.SimBlocked(
                            "Exact Web-selected SIM identity is not available locally. No fallback SIM will be used.",
                            cloudQueueRepo.countsForCurrentDevice()
                        )
                    } else {
                        cloudQueueRepo.sync(
                            CloudQueueExecutionIdentity(
                                deviceId = paired.deviceId,
                                gatewaySimId = binding.simId,
                                subscriptionId = binding.subscriptionId,
                                slotIndex = binding.slotIndex,
                                simIdentityHash = exactLocalSim.simIdentityHash!!
                            )
                        )
                    }
                }
                is GatewayInventoryState.Missing -> CloudQueueState.SimBlocked(
                    "Exact Web-selected SIM is Missing. Cloud queue sync is blocked; another SIM will not be substituted.",
                    cloudQueueRepo.countsForCurrentDevice()
                )
                is GatewayInventoryState.Unbound -> CloudQueueState.SimBlocked(
                    "Select the sending SIM in BulkText Web before syncing cloud jobs.",
                    cloudQueueRepo.countsForCurrentDevice()
                )
                GatewayInventoryState.Offline -> CloudQueueState.Offline(cloudQueueRepo.countsForCurrentDevice())
                GatewayInventoryState.CredentialRejected -> CloudQueueState.CredentialRejected(cloudQueueRepo.countsForCurrentDevice())
                GatewayInventoryState.PermissionRequired -> CloudQueueState.SimBlocked(
                    "Phone permission is required to read the exact SIM identity.",
                    cloudQueueRepo.countsForCurrentDevice()
                )
                GatewayInventoryState.Unpaired -> CloudQueueState.Error("Pair this phone first.", cloudQueueRepo.countsForCurrentDevice())
                GatewayInventoryState.Reporting, GatewayInventoryState.NotReported -> CloudQueueState.Error(
                    "SIM inventory is not ready yet.", cloudQueueRepo.countsForCurrentDevice()
                )
                is GatewayInventoryState.Error -> CloudQueueState.Error(refreshedInventory.message, cloudQueueRepo.countsForCurrentDevice())
            }

            // Recovery can race with queue/callback updates, so poll once more
            // after the queue pass. This remains recovery-only and cannot call SmsManager.
            try { recoveredThisSync += app.cloudDeliveryRepository.syncRecoveryRequests() } catch (_: Exception) { }

            handler.post {
                cloudQueueSyncInFlight = false
                inventoryState = refreshedInventory
                cloudQueueState = queueResult
                if (refreshedInventory is GatewayInventoryState.CredentialRejected || queueResult is CloudQueueState.CredentialRejected) {
                    currentState = GatewayIdentityState.CredentialRejected
                    renderIdentity()
                }
                renderCloudInventory()
                renderCloudQueue()
                renderBackgroundGateway()
                renderCloudExecution()
                if (recoveredThisSync > 0) {
                    Toast.makeText(this, "Applied $recoveredThisSync Web recovery request(s) without resending", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderCloudQueue() {
        if (!::cloudQueueStatus.isInitialized) return
        fun countsText(counts: com.bulktext.gateway.queue.CloudQueueCounts): String {
            val last = counts.latestSyncAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it)) } ?: "never"
            return "Persisted jobs: ${counts.persistedJobs} • Unacknowledged: ${counts.unacknowledgedJobs} • ACKed: ${counts.acknowledgedJobs}\nLast sync: $last"
        }
        when (val state = cloudQueueState) {
            CloudQueueState.NotSynced -> {
                val counts = cloudQueueRepo.countsForCurrentDevice()
                if (counts.latestSyncAt != null && app.backgroundGatewayPreferences.isEnabled()) {
                    cloudQueueStatus.text = "Cloud Queue status: Background sync active\n${countsText(counts)}"
                    cloudQueueStatus.setTextColor(Color.rgb(0, 128, 0))
                } else {
                    cloudQueueStatus.text = "Cloud Queue status: Not synced yet\n${countsText(counts)}"
                    cloudQueueStatus.setTextColor(Color.rgb(100, 110, 120))
                }
            }
            CloudQueueState.Syncing -> {
                cloudQueueStatus.text = "Cloud Queue status: Syncing…\nRefreshing exact SIM inventory before claiming work."
                cloudQueueStatus.setTextColor(Color.rgb(200, 120, 0))
            }
            is CloudQueueState.Ready -> {
                val execution = (currentState as? GatewayIdentityState.Paired)?.let { app.cloudQueueStore.executionCounts(it.deviceId) }
                val executionText = execution?.let { "Execution: Ready ${it.ready} • Submitting ${it.submitting} • Submitted ${it.submitted} • UNKNOWN ${it.unknown}" } ?: "Execution: unavailable until paired"
                cloudQueueStatus.text = "Cloud Queue status: Ready\nClaimed this sync: ${state.claimedJobs} • ACKed this sync: ${state.acknowledgedThisSync}\n${countsText(state.counts)}\n$executionText"
                cloudQueueStatus.setTextColor(Color.rgb(0, 128, 0))
            }
            is CloudQueueState.Offline -> {
                cloudQueueStatus.text = "Cloud Queue status: Offline\nDurable local jobs retained.\n${countsText(state.counts)}"
                cloudQueueStatus.setTextColor(Color.rgb(180, 100, 0))
            }
            is CloudQueueState.CredentialRejected -> {
                cloudQueueStatus.text = "Cloud Queue status: Credential rejected — re-pair required\n${countsText(state.counts)}"
                cloudQueueStatus.setTextColor(Color.RED)
            }
            is CloudQueueState.SimBlocked -> {
                cloudQueueStatus.text = "Cloud Queue status: Blocked\n${state.message}\n${countsText(state.counts)}"
                cloudQueueStatus.setTextColor(Color.RED)
            }
            is CloudQueueState.Error -> {
                cloudQueueStatus.text = "Cloud Queue status: Error\n${state.message}\n${countsText(state.counts)}"
                cloudQueueStatus.setTextColor(Color.RED)
            }
        }
    }


    private fun toggleBackgroundGateway() {
        val prefs = app.backgroundGatewayPreferences
        if (prefs.isEnabled()) {
            prefs.disable()
            stopService(Intent(this, BackgroundGatewayService::class.java))
            Toast.makeText(this, "Sending stopped; in-flight SMS callbacks can still settle", Toast.LENGTH_SHORT).show()
            renderBackgroundGateway()
            renderCloudExecution()
            return
        }

        val paired = currentState as? GatewayIdentityState.Paired
            ?: error("Pair and authenticate this phone first.")
        require(Telephony.permitted(this)) { "Grant SMS and phone permissions first." }
        val counts = app.cloudQueueStore.executionCounts(paired.deviceId)
        require(BackgroundGatewayPolicy.canEnableForRecovery(counts.submitting)) {
            "A cloud SMS submission is still in progress."
        }
        // 0.18.3: UNKNOWN blocks SmsManager execution, not the persistent recovery service.
        // Enabling here lets the foreground gateway auto-poll queue/callback/recovery
        // control traffic until explicit Web recovery clears the ambiguity.
        prefs.enable()
        if (counts.unknown > 0) {
            prefs.pause("Gateway recovery mode: UNKNOWN SMS blocks sending while automatic sync waits for explicit Web recovery.")
        } else {
            prefs.clearPause()
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        }
        startForegroundService(Intent(this, BackgroundGatewayService::class.java))
        Toast.makeText(this, if (counts.unknown > 0) "Background gateway active in recovery mode" else "Background gateway active", Toast.LENGTH_SHORT).show()
        renderBackgroundGateway()
        renderCloudExecution()
    }

    private fun ensureBackgroundGatewayService() {
        val prefs = app.backgroundGatewayPreferences
        if (!prefs.isEnabled()) return
        try {
            startForegroundService(Intent(this, BackgroundGatewayService::class.java))
        } catch (_: Exception) {
            prefs.setStatus("Gateway is enabled but Android did not start the background service. Open the app and try again.")
        }
    }

    private fun renderBackgroundGateway() {
        if (!::backgroundGatewayStatus.isInitialized || !::backgroundGatewayButton.isInitialized) return
        val prefs = app.backgroundGatewayPreferences
        val paused = prefs.pausedReason()
        val paired = currentState as? GatewayIdentityState.Paired
        val counts = paired?.let { app.cloudQueueStore.executionCounts(it.deviceId) }

        when {
            !prefs.isEnabled() -> {
                backgroundGatewayStatus.text = "Background gateway: Disabled\nEnable once, then send campaigns from Web without returning here for each campaign."
                backgroundGatewayStatus.setTextColor(Color.rgb(100, 110, 120))
                backgroundGatewayButton.text = "Enable background gateway"
            }
            paused != null || (counts?.unknown ?: 0) > 0 -> {
                val reason = paused ?: "UNKNOWN cloud SMS requires operator review. No automatic retry."
                backgroundGatewayStatus.text = "Background gateway: PAUSED\n$reason\n${backgroundSyncHealthText(prefs)}"
                backgroundGatewayStatus.setTextColor(Color.RED)
                backgroundGatewayButton.text = "Disable background gateway"
            }
            else -> {
                val activated = if (prefs.activatedAt() > 0L) DateFormat.getDateTimeInstance().format(Date(prefs.activatedAt())) else "unknown"
                backgroundGatewayStatus.text = "Background gateway: ACTIVE\nActivated $activated\n${prefs.lastStatus() ?: "Waiting for Web-authorized campaigns."}\n${backgroundSyncHealthText(prefs)}"
                backgroundGatewayStatus.setTextColor(Color.rgb(0, 128, 0))
                backgroundGatewayButton.text = "Disable background gateway"
            }
        }
    }

    private fun backgroundSyncHealthText(prefs: BackgroundGatewayPreferences): String {
        fun stamp(value: Long): String = if (value > 0L) DateFormat.getDateTimeInstance().format(Date(value)) else "not yet"
        return "Control cycle: ${stamp(prefs.lastControlCycleAt())} • Callback flush: ${stamp(prefs.lastCallbackFlushAt())} • Recovery sync: ${stamp(prefs.lastRecoverySyncAt())}"
    }

    private fun previewCloudCampaignSend() {
        val paired = currentState as? GatewayIdentityState.Paired ?: error("Pair and authenticate this phone first.")
        require(Telephony.permitted(this)) { "Grant SMS and phone permissions first." }
        if (Build.VERSION.SDK_INT >= 33) {
            require(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                "Enable notifications before cloud execution."
            }
        }
        val global = app.cloudQueueStore.executionCounts(paired.deviceId)
        require(global.submitting == 0) { "A cloud SMS submission is still in progress." }
        require(global.unknown == 0) { "A prior cloud SMS is UNKNOWN. Resolve it from BulkText Web; no automatic retry is permitted." }
        val first = app.cloudQueueStore.nextReady(paired.deviceId)
            ?: error("No ACKed READY cloud job is available. The background gateway handles new Web jobs while active; manual sync remains recovery-only.")
        val campaign = app.cloudQueueStore.campaignExecutionCounts(paired.deviceId, first.campaignId)
        val batchSize = com.bulktext.gateway.queue.CloudExecutionPolicy.boundedBatchSize(campaign.ready)
        require(batchSize > 0) { "No READY jobs remain for this campaign." }
        val masked = if (first.phone.length >= 4) "…${first.phone.takeLast(4)}" else "masked"
        AlertDialog.Builder(this)
            .setTitle("Send campaign batch?")
            .setMessage(
                "Campaign ${first.campaignId.take(8)}…\n" +
                    "$batchSize READY job${if (batchSize == 1) "" else "s"} in this batch" +
                    "${if (campaign.ready > batchSize) " (${campaign.ready} READY total; 0.18 manual recovery limits each confirmation to ${com.bulktext.gateway.queue.CloudExecutionPolicy.MAX_BATCH_JOBS})" else ""}\n" +
                    "First recipient $masked\n" +
                    "Web-authorized SIM ${first.slotIndex + 1} • subscription ${first.subscriptionId}\n\n" +
                    "BulkText re-checks the exact Web-selected SIM before every SMS. The batch stops on the first blocking error or UNKNOWN. " +
                    "SENT and DELIVERED are callback states. UNKNOWN/mixed outcomes stop the batch and are never auto-retried."
            )
            .setNegativeButton("Back", null)
            .setPositiveButton("Send $batchSize") { _, _ ->
                guard {
                    val permit = requireNotNull(app.backgroundGatewayPreferences.submissionGate.capture(automatic = false))
                    startForegroundService(
                        Intent(this, CloudSendingService::class.java)
                            .putExtra(CloudSendingService.EXTRA_STOP_GENERATION, permit.generation)
                            .putExtra(CloudSendingService.EXTRA_AUTOMATIC, false)
                            .putExtra(CloudSendingService.EXTRA_DEVICE_ID, paired.deviceId)
                            .putExtra(CloudSendingService.EXTRA_CAMPAIGN_ID, first.campaignId)
                    )
                    Toast.makeText(this, "Campaign batch started", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun renderCloudExecution() {
        if (!::cloudExecutionStatus.isInitialized) return
        val paired = currentState as? GatewayIdentityState.Paired
        if (paired == null) {
            cloudExecutionStatus.text = "Cloud execution: Pair this phone first"
            cloudExecutionStatus.setTextColor(Color.rgb(100, 110, 120))
            return
        }
        val counts = app.cloudQueueStore.executionCounts(paired.deviceId)
        val backgroundActive = app.backgroundGatewayPreferences.isEnabled() && app.backgroundGatewayPreferences.pausedReason() == null
        cloudExecutionStatus.text =
            "Cloud execution • Ready ${counts.ready} • Submitting ${counts.submitting} • Submitted ${counts.submitted} • Sent ${counts.sent} • Delivered ${counts.delivered} • Failed ${counts.failed} • UNKNOWN ${counts.unknown}\n" +
                if (backgroundActive) {
                    "Background gateway is active. SENT/DELIVERED callbacks and recovery events sync automatically. UNKNOWN pauses sending but the gateway stays online for explicit Web recovery."
                } else {
                    "Background gateway is not active. Manual/recovery execution can process up to ${com.bulktext.gateway.queue.CloudExecutionPolicy.MAX_BATCH_JOBS} READY jobs. UNKNOWN blocks further sending."
                }
        cloudExecutionStatus.setTextColor(if (counts.unknown > 0) Color.RED else Color.rgb(0, 128, 0))
        if (::cloudCallbackDiagnosticsStatus.isInitialized) {
            val diagnostics = app.cloudQueueStore.latestCallbackDiagnostics(paired.deviceId, 6)
            cloudCallbackDiagnosticsStatus.text = if (diagnostics.isEmpty()) {
                "Callback diagnostics: No callbacks recorded yet"
            } else buildString {
                append("Latest callback diagnostics (newest first):")
                diagnostics.forEach { d ->
                    val error = d.errorCode?.let { " • errorCode $it" } ?: ""
                    val elapsed = d.elapsedMs?.let { " • +${it}ms" } ?: ""
                    val dup = if (d.duplicateCount > 0) " • duplicates ${d.duplicateCount}" else ""
                    append("\n${d.kind.uppercase()} part ${d.partIndex + 1} • resultCode ${d.resultCode} (${d.symbolicResult})$error$elapsed$dup")
                }
            }
        }
        if (::manualCampaignButton.isInitialized) manualCampaignButton.isEnabled = !backgroundActive && counts.submitting == 0 && counts.unknown == 0
    }

    private fun renderIdentity() {
        val instId = identityRepo.getInstallationId()
        when (val s = currentState) {
            is GatewayIdentityState.Unpaired -> {
                pairingStatus.text = "Status: Not paired\nInstallation ID: ${instId.take(8)}…"
                pairingStatus.setTextColor(Color.rgb(100, 110, 120))
                pairingCodeInput.visibility = View.VISIBLE
                pairBtn.isEnabled = true
                unpairBtn.isEnabled = false
                if (inventoryState !is GatewayInventoryState.Reporting) inventoryState = GatewayInventoryState.Unpaired
                renderCloudInventory()
            }
            is GatewayIdentityState.Pairing -> {
                pairingStatus.text = "Status: Pairing with server…"
                pairingStatus.setTextColor(Color.rgb(200, 120, 0))
                pairBtn.isEnabled = false
            }
            is GatewayIdentityState.Paired -> {
                pairingStatus.text = "Status: Paired & Valid\nDevice: ${s.deviceName} • ID: ${s.deviceId.take(8)}…"
                pairingStatus.setTextColor(Color.rgb(0, 128, 0))
                pairingCodeInput.visibility = View.GONE
                pairBtn.isEnabled = false
                unpairBtn.isEnabled = true
            }
            is GatewayIdentityState.Offline -> {
                pairingStatus.text = "Status: Offline (Local pairing retained)\nInstallation ID: ${instId.take(8)}…"
                pairingStatus.setTextColor(Color.rgb(180, 100, 0))
                unpairBtn.isEnabled = true
            }
            is GatewayIdentityState.CredentialExpired -> {
                pairingStatus.text = "Status: Credential Expired — Re-pairing required"
                pairingStatus.setTextColor(Color.RED)
                pairingCodeInput.visibility = View.VISIBLE
                pairBtn.isEnabled = true
                unpairBtn.isEnabled = true
            }
            is GatewayIdentityState.CredentialRejected -> {
                pairingStatus.text = "Status: Credential Revoked/Rejected — Re-pairing required"
                pairingStatus.setTextColor(Color.RED)
                pairingCodeInput.visibility = View.VISIBLE
                pairBtn.isEnabled = true
                unpairBtn.isEnabled = true
                inventoryState = GatewayInventoryState.CredentialRejected
                cloudQueueState = CloudQueueState.CredentialRejected(cloudQueueRepo.countsForCurrentDevice())
                renderCloudInventory()
                renderCloudQueue()
            }
            is GatewayIdentityState.Error -> {
                pairingStatus.text = "Status: Error — ${s.message}"
                pairingStatus.setTextColor(Color.RED)
                pairBtn.isEnabled = true
            }
        }
    }

    private fun text(value: String, size: Int): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.rgb(24, 43, 57))
        setPadding(0, 12, 0, 12)
        this@MainActivity.layout.addView(this)
    }

    private fun edit(h: String, lines: Int): EditText = EditText(this).apply {
        hint = h
        minLines = lines
        maxLines = 8
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        this@MainActivity.layout.addView(this)
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { guard(action) }
        this@MainActivity.layout.addView(this)
    }

    private fun guard(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Cannot continue").setMessage(e.message ?: e.javaClass.simpleName).setPositiveButton("OK", null).show()
        }
    }

    private fun grant() {
        val requested = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = requested.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 10) else refreshSims()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        refreshSims()
        if (currentState is GatewayIdentityState.Paired) refreshCloudInventory()
    }

    private fun refreshSims() {
        val previous = sims.getOrNull(sim.selectedItemPosition)?.subscriptionId
        sims = try { Telephony.sims(this) } catch (_: Exception) { emptyList() }
        val labels = if (sims.isEmpty()) listOf("No active SIM / permissions required") else sims.map { "SIM ${it.simSlotIndex + 1} • ${it.carrierName} • subscription ${it.subscriptionId}" }
        sim.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val index = sims.indexOfFirst { it.subscriptionId == previous }
        if (index >= 0) sim.setSelection(index)
        render()
    }

    private fun preview() {
        require(Telephony.permitted(this)) { "Grant SMS and phone permissions first." }
        if (Build.VERSION.SDK_INT >= 33) require(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { "Enable notifications for the visible Stop control." }
        require(consent.isChecked) { "Confirm recipient consent and possible charges." }
        require(store.latest()?.status !in listOf("READY", "RUNNING", "PAUSED")) { "Resume or cancel the existing session first." }
        val selected = sims.getOrNull(sim.selectedItemPosition) ?: error("Select an active SIM")
        val phones = Policy.recipients(numbers.text.toString())
        val message = body.text.toString()
        require(message.isNotBlank()) { "Enter a message" }
        require(message.length <= 2000) { "Prototype message limit is 2,000 UTF-16 units" }
        val parts = Telephony.manager(this, selected.subscriptionId).divideMessage(message).size
        Policy.checkBudget(phones.size, parts)
        AlertDialog.Builder(this).setTitle("Send real test SMS?")
            .setMessage("SIM ${selected.simSlotIndex + 1}: ${selected.carrierName}\n${phones.size} unique recipients × $parts parts = ${phones.size * parts} segments\n\n${phones.joinToString("\n")}\n\n$message\n\nPackage eligibility/charges are determined by your operator. 10-second spacing is a test setting, not an operator-approved rate.")
            .setNegativeButton("Back", null).setPositiveButton("Send test") { _, _ -> guard {
                val s = Session("", selected.subscriptionId, Telephony.key(selected), "READY")
                Telephony.check(this, s)
                val id = store.create(s.sub, s.simKey, phones, message, parts)
                launch(id)
            } }.show()
    }

    private fun launch(id: String) {
        if (Build.VERSION.SDK_INT >= 33) require(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { "Enable notifications before starting a session" }
        startForegroundService(Intent(this, SendingService::class.java).putExtra("session", id))
        render()
    }

    private fun render() {
        if (!::status.isInitialized) return
        permissions.text = getString(R.string.permission_status, if (Telephony.permitted(this)) "granted" else "required", Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
        renderBackgroundGateway()
        renderCloudExecution()
        renderCloudQueue()
        val s = store.latest()
        status.text = if (s == null) "No session yet. Grant permissions and preview a small test." else buildString {
            append("Session: ${s.status} • subscription ${s.sub}\n")
            store.jobs(s.id).forEach { j -> append("\n…${j.phone.takeLast(4)} • ${j.status} • ${j.parts} parts\n${j.note}\n") }
        }
    }

    override fun onResume() {
        super.onResume()
        activityForeground = true
        if (::sim.isInitialized) refreshSims()
        checkIdentityState()
        ensureBackgroundGatewayService()
        renderBackgroundGateway()
        renderCloudQueue()
        renderCloudExecution()
        handler.post(refresh)
        handler.removeCallbacks(autoSync)
        handler.postDelayed(autoSync, 1500)
    }

    override fun onPause() {
        activityForeground = false
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(autoSync)
        super.onPause()
    }

    @Deprecated("Platform callback retained to avoid additional dependencies in the lab")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 20 && result == RESULT_OK) guard {
            val uri = data?.data ?: error("No export location")
            val stream = contentResolver.openOutputStream(uri) ?: error("Unable to open export")
            val combined = JSONObject()
                .put("version", "0.18.3")
                .put("exportedAt", System.currentTimeMillis())
                .put("localSmsLab", JSONObject(store.export()))
                .put("cloudQueue", JSONObject(cloudQueueRepo.diagnosticsForCurrentDevice()))
                .put("privacy", "Recipient phone numbers, SMS bodies, gateway credentials and Supabase keys are excluded from diagnostics.")
            stream.bufferedWriter().use { it.write(combined.toString(2)) }
            Toast.makeText(this, "Diagnostics exported", Toast.LENGTH_SHORT).show()
        }
    }
}
