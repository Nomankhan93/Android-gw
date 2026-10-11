package com.bulktext.gateway

import com.bulktext.gateway.cloud.*
import com.bulktext.gateway.queue.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=GatewayApp::class, manifest=Config.NONE)
class RecoveryContract0185Test {
    private val store get() = (RuntimeEnvironment.getApplication() as GatewayApp).cloudQueueStore
    private fun id()=UUID.randomUUID().toString()
    private fun job(device: String="owner"): Pair<GatewayQueueJob,LocalCloudAttempt> {
        val j=GatewayQueueJob(id(),id(),id(),2,"+923001234567","fixture","GSM-7",1,id(),1,1L,100000L,id(),1,0,"a".repeat(64))
        store.persist(device,j);store.markAcknowledged(device,listOf(j.receipt()))
        val a=store.ensurePreparedAttempt(device,j.jobId,1);store.bindServerAttempt(a.clientAttemptId,1)
        return j to a
    }
    private fun submitted(device: String="owner"): Pair<GatewayQueueJob,LocalCloudAttempt> {
        val p=job(device);assertTrue(store.beginSubmission(device,p.first.jobId,p.second.clientAttemptId));store.markSubmitted(device,p.first.jobId);return p
    }
    private fun callback(a:LocalCloudAttempt,code:Int=-1,kind:String="sent",e:DeliveryEvidence?=null) = store.recordAttemptCallback(a.clientAttemptId,id(),0,kind,code,deliveryEvidence=e)
    private fun recovery(j:GatewayQueueJob,a:LocalCloudAttempt,action:String="skip_unknown")=GatewayRecoveryRequest(id(),action,j.jobId,j.campaignId,a.clientAttemptId,1,1L)

    @Test fun deviceOutboxesAreIsolated() {
        submitted("first");submitted("second")
        assertEquals(1,store.pendingAttemptEvents("first").size)
        assertEquals(1,store.pendingAttemptEvents("second").size)
        assertTrue(store.pendingAttemptEvents("third").isEmpty())
    }
    @Test fun callbackRetryRetainsExactPayload() {
        val (_,a)=submitted();callback(a,kind="delivery",e=DeliveryEvidence("3gpp",32,"b".repeat(64)))
        val event=store.pendingAttemptEvents("owner").single { it.event.eventType=="delivery" }.event
        store.noteAttemptEventSyncFailure(event.eventId)
        assertEquals(event,store.pendingAttemptEvents("owner").single { it.event.eventId==event.eventId }.event)
    }
    @Test fun terminalRejectionDoesNotPoisonLaterEvents() {
        val (_,a)=submitted();callback(a)
        val first=store.pendingAttemptEvents("owner").first().event
        store.completeReport(first.eventId,GatewayAttemptReport("",a.clientAttemptId,0,"unknown",false,0,0,0,"rejected"))
        assertEquals(1,store.pendingAttemptEvents("owner").size)
        assertEquals("SENT",store.currentAttemptOutcome(a.clientAttemptId)!!.state)
    }
    @Test fun recoveryIntentPersistsAndNotApplicableIsAcked() {
        val (j,a)=submitted();val r=recovery(j,a)
        assertEquals(RecoveryApplyResult.NOT_APPLICABLE,store.applyRecoveryRequest("owner",r))
        assertEquals(listOf(r.recoveryId to "not_applicable"),store.pendingRecoveryAcks("owner"))
        assertTrue(store.pendingRecoveryAcks("another").isEmpty())
        store.applyRecoveryRequest("owner",r)
        assertEquals(1,store.pendingRecoveryAcks("owner").size)
        store.markRecoveryAcked("owner",r.recoveryId);assertTrue(store.pendingRecoveryAcks("owner").isEmpty())
    }
    @Test fun resolvedUnknownNeverReblocksOnLateCallback() {
        val (j,a)=submitted();callback(a,1);val r=recovery(j,a)
        assertEquals(RecoveryApplyResult.APPLIED,store.applyRecoveryRequest("owner",r))
        callback(a,1);assertEquals(0,store.executionCounts("owner").unknown)
        assertEquals(listOf(r.recoveryId to "applied"),store.pendingRecoveryAcks("owner"))
    }
    @Test fun conflictCannotBeHealedByMatchingCallbacks() {
        val (_,a)=submitted();callback(a);callback(a,1);callback(a)
        assertEquals("UNKNOWN",store.currentAttemptOutcome(a.clientAttemptId)!!.state)
        assertEquals(1,store.executionCounts("owner").unknown)
    }
    @Test fun lateSuccessResolvesTimeoutWithoutResend() {
        val (j,a)=submitted();store.markAttemptCallbackTimeout("owner",j.jobId,a.clientAttemptId,"timeout")
        callback(a);assertEquals("SENT",store.currentAttemptOutcome(a.clientAttemptId)!!.state)
        assertNull(store.nextReady("owner"));assertFalse(store.beginSubmission("owner",j.jobId,a.clientAttemptId))
    }
    @Test fun deliveryNeedsEvidenceAndConflictsLatch() {
        val (_,a)=submitted();callback(a);callback(a,kind="delivery")
        assertEquals("SENT",store.currentAttemptOutcome(a.clientAttemptId)!!.state)
        callback(a,kind="delivery",e=DeliveryEvidence("3gpp",0,"c".repeat(64)))
        assertEquals("DELIVERED",store.currentAttemptOutcome(a.clientAttemptId)!!.state)
        callback(a,kind="delivery",e=DeliveryEvidence("3gpp",32,"d".repeat(64)))
        assertEquals("DELIVERED",store.currentAttemptOutcome(a.clientAttemptId)!!.state)
        callback(a,kind="delivery",e=DeliveryEvidence("3gpp",64,"e".repeat(64)))
        assertEquals("UNKNOWN",store.currentAttemptOutcome(a.clientAttemptId)!!.state)
    }
    @Test fun blockedJobDoesNotStarveNextJob() {
        val (j,a)=job();store.blockBeforeSubmission("owner",j.jobId,"recipient_ineligible")
        val (next,_)=job();assertEquals(next.jobId,store.nextReady("owner")!!.jobId)
        assertFalse(store.beginSubmission("owner",j.jobId,a.clientAttemptId))
    }
    @Test fun simBlockRequiresRecheckAndNeverFallback() {
        val (j,a)=job();store.blockBeforeSubmission("owner",j.jobId,"sim_gate")
        assertNull(store.nextReady("owner"));assertFalse(store.beginSubmission("owner",j.jobId,a.clientAttemptId))
    }
    @Test fun failedSyncDoesNotChangeSuccessfulTimestamp() {
        store.recordSync("owner",1000,null);store.recordSync("owner",2000,"Network unavailable")
        assertEquals(1000L,store.counts("owner").latestSyncAt)
        assertEquals(2000L,store.syncHealth("owner").first)
    }
    @Test fun legacySafeRetryIsRejectedDurably() {
        val (j,a)=submitted();callback(a,1);val r=recovery(j,a,"safe_retry")
        assertEquals(RecoveryApplyResult.REJECTED_UNSAFE,store.applyRecoveryRequest("owner",r))
        assertEquals(listOf(r.recoveryId to "rejected"),store.pendingRecoveryAcks("owner"));assertNull(store.nextReady("owner"))
    }
    @Test fun rawCdmaAndGsmStatuses() {
        fun state(f:String,s:Int)=DeliveryEvidence(f,s,"a".repeat(64)).state()
        assertEquals("DELIVERED",state("3gpp2",0));assertEquals("FAILED",state("3gpp2",2 shl 24))
        assertEquals("PENDING",state("3gpp2",2));assertEquals("PENDING",state("3gpp",32))
        assertEquals("FAILED",state("3gpp",255));assertEquals("DELIVERED",state("3gpp",31))
    }

    private fun credentials(device:String) = object : com.bulktext.gateway.identity.CredentialStore {
        override fun getDeviceId()=device
        override fun getDeviceName()="Fixture"
        override fun getCredential()="fixture-credential"
        override fun getCredentialVersion()=1
        override fun getExpiresAt()=Long.MAX_VALUE
        override fun clear()=false
        override fun saveCredential(deviceId:String,credential:String,version:Int,expiresAt:Long,deviceName:String)=false
    }
    private class FakeApi : GatewayDeliveryApi {
        var requests=emptyList<GatewayRecoveryRequest>()
        var loseAck=false
        var rejectAuth=false
        var offline=false
        val acks=mutableListOf<Pair<String,String>>()
        val events=mutableListOf<GatewayAttemptEvent>()
        override fun beginGatewayMessageAttempt(deviceId:String,credential:String,jobId:String,clientAttemptId:String,expectedParts:Int) =
            GatewayAttemptStart("",clientAttemptId,0,"blocked",false,0,"blocked",false,"recipient_ineligible")
        override fun reportGatewayMessageAttemptEvent(deviceId:String,credential:String,event:GatewayAttemptEvent):GatewayAttemptReport {
            if(rejectAuth) throw CloudException.CredentialRejectedException()
            if(offline) throw CloudException.NetworkException("offline")
            events.add(event)
            return GatewayAttemptReport("",event.clientAttemptId,0,"unknown",false,0,0,0,"tombstoned")
        }
        override fun listGatewayMessageRecoveryRequests(deviceId:String,credential:String,limit:Int)=requests
        override fun acknowledgeGatewayMessageRecovery(deviceId:String,credential:String,recoveryId:String,outcome:String):Boolean {
            acks.add(recoveryId to outcome)
            if(loseAck) { loseAck=false;requests=emptyList();throw CloudException.NetworkException("lost response") }
            return true
        }
    }
    @Test fun lostAckReplaysEvenWhenServerNoLongerListsRequest() {
        val (j,a)=submitted();callback(a,1)
        val api=FakeApi();val r=recovery(j,a);api.requests=listOf(r);api.loseAck=true
        val repo=CloudDeliveryRepository(credentials("owner"),api,store)
        try {repo.syncRecoveryRequests();fail("expected lost ACK")} catch(_:CloudException.NetworkException) {}
        assertEquals(0,store.executionCounts("owner").unknown)
        assertEquals(1,store.pendingRecoveryAcks("owner").size)
        CloudDeliveryRepository(credentials("owner"),api,store).syncRecoveryRequests()
        assertEquals(listOf(r.recoveryId to "applied",r.recoveryId to "applied"),api.acks)
        assertTrue(store.pendingRecoveryAcks("owner").isEmpty())
    }
    @Test fun authFailureRetainsOutboxAndTombstoneDrainsOnlyCurrentOwner() {
        submitted("other");submitted("owner")
        val api=FakeApi();api.rejectAuth=true
        val repo=CloudDeliveryRepository(credentials("owner"),api,store)
        try {repo.flushEvents();fail("expected auth failure")} catch(_:CloudException.CredentialRejectedException) {}
        assertEquals(1,store.pendingAttemptEvents("owner").size)
        api.rejectAuth=false;assertEquals(1,repo.flushEvents())
        assertTrue(store.pendingAttemptEvents("owner").isEmpty());assertEquals(1,store.pendingAttemptEvents("other").size)
    }
    @Test fun backendBlockedGrantNeverBindsOrSubmits() {
        val (j,a)=job();val repo=CloudDeliveryRepository(credentials("owner"),FakeApi(),store)
        assertFalse(repo.beginAttempt(j.jobId,a).canSubmit)
        assertNull(store.nextReady("owner"));assertFalse(store.beginSubmission("owner",j.jobId,a.clientAttemptId))
    }
    @Test fun upgradeSixPreservesResolutionAndBackfillsPendingAck() {
        val (j,a)=submitted();callback(a,1);val r=recovery(j,a);store.applyRecoveryRequest("owner",r)
        val db=store.writableDatabase
        db.execSQL("DROP TABLE recovery_ack_outbox")
        for((table,cols) in mapOf("cloud_attempts" to listOf("conflict_latched"),"cloud_jobs" to listOf("block_code","block_retry_at"),"cloud_callback_outbox" to listOf("delivery_format","delivery_status","delivery_sha256","disposition","device_id"),"queue_sync_meta" to listOf("last_attempt_at")))
            for(col in cols) db.execSQL("ALTER TABLE $table DROP COLUMN $col")
        db.version=6;store.close()
        val reopened=SQLiteCloudQueueStore(RuntimeEnvironment.getApplication())
        assertEquals(7,reopened.writableDatabase.version)
        assertEquals(0,reopened.executionCounts("owner").unknown)
        assertEquals(listOf(r.recoveryId to "applied"),reopened.pendingRecoveryAcks("owner"))
        assertTrue(reopened.pendingAttemptEvents("other").isEmpty())
        assertTrue(reopened.pendingAttemptEvents("owner").isNotEmpty())
        reopened.close()
    }
    @Test fun timeoutCannotUndoOperatorResolution() {
        val (j,a)=submitted();callback(a,1);store.applyRecoveryRequest("owner",recovery(j,a))
        assertFalse(store.markAttemptCallbackTimeout("owner",j.jobId,a.clientAttemptId,"late timer"))
        assertEquals(0,store.executionCounts("owner").unknown)
    }
    @Test fun changedUuidPayloadRollsBackLocalTruth() {
        val (_,a)=submitted();val uuid=id()
        store.recordAttemptCallback(a.clientAttemptId,uuid,0,"sent",-1,atMillis=1000)
        assertThrows(IllegalStateException::class.java) {store.recordAttemptCallback(a.clientAttemptId,uuid,0,"sent",1,atMillis=1000)}
        assertEquals("SENT",store.currentAttemptOutcome(a.clientAttemptId)!!.state)
        assertEquals(-1,store.pendingAttemptEvents("owner").single {it.event.eventId==uuid}.event.resultCode)
    }
    @Test fun failedDeliveryBroadcastCannotProveDelivery() {
        val (_,a)=submitted();callback(a)
        callback(a,code=1,kind="delivery",e=DeliveryEvidence("3gpp",0,"a".repeat(64)))
        assertEquals("SENT",store.currentAttemptOutcome(a.clientAttemptId)!!.state)
    }
    @Test fun preparedAndSubmittedAreDistinct() {
        val (j,a)=job()
        assertEquals(1,store.executionCounts("owner").prepared)
        assertEquals(0,store.executionCounts("owner").submitted)
        assertTrue(store.beginSubmission("owner",j.jobId,a.clientAttemptId));store.markSubmitted("owner",j.jobId)
        assertEquals(0,store.executionCounts("owner").prepared)
        assertEquals(1,store.executionCounts("owner").submitted)
    }
    @Test fun operationHealthSurvivesRestartAndOtherOperationSuccess() {
        val context=RuntimeEnvironment.getApplication()
        val health=BackgroundGatewayPreferences(context)
        health.recordOperation("owner","callbacks",at=1000)
        health.recordOperation("owner","callbacks",CloudException.ServerContractException("secret must not display"),2000)
        health.recordOperation("owner","recovery",at=3000)
        val reopened=BackgroundGatewayPreferences(context)
        assertEquals(1000L,reopened.operationSuccessAt("owner","callbacks"))
        assertTrue(reopened.operationErrors("owner").contains("incompatible"))
        assertFalse(reopened.operationErrors("owner").contains("secret"))
        assertEquals("",reopened.operationErrors("other"))
        reopened.recordOperation("owner","callbacks",at=4000)
        assertEquals("",reopened.operationErrors("owner"))
    }
    @Test fun repositoryPersistsFailureAndClearsOnlyAfterSuccess() {
        submitted();val api=FakeApi();api.rejectAuth=true
        val health=BackgroundGatewayPreferences(RuntimeEnvironment.getApplication())
        val repo=CloudDeliveryRepository(credentials("owner"),api,store,health)
        assertThrows(CloudException.CredentialRejectedException::class.java) {repo.flushEvents()}
        assertNotNull(health.operationError("owner","callbacks"))
        assertEquals(1,store.pendingAttemptEvents("owner").size)
        api.rejectAuth=false;assertEquals(1,repo.flushEvents())
        assertNull(health.operationError("owner","callbacks"))
        assertTrue(health.operationSuccessAt("owner","callbacks")>0)
    }
    @Test fun networkFailureCannotBeRecordedAsSuccessfulCallbackSync() {
        submitted();val api=FakeApi();api.offline=true
        val health=BackgroundGatewayPreferences(RuntimeEnvironment.getApplication())
        health.recordOperation("owner","callbacks",at=1000)
        val repo=CloudDeliveryRepository(credentials("owner"),api,store,health)
        assertThrows(CloudException.NetworkException::class.java) {repo.flushEvents()}
        assertEquals(1000L,health.operationSuccessAt("owner","callbacks"))
        assertNotNull(health.operationError("owner","callbacks"))
        assertEquals(1,store.pendingAttemptEvents("owner").size)
    }
}
