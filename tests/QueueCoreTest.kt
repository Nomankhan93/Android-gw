import com.bulktext.gateway.cloud.*
import com.bulktext.gateway.identity.CredentialStore
import com.bulktext.gateway.queue.*

private fun check(value: Boolean, message: String) { if (!value) error(message) }

private val deviceId = "11111111-1111-4111-8111-111111111111"
private val simId = "22222222-2222-4222-8222-222222222222"
private val identity = CloudQueueExecutionIdentity(deviceId, simId, 11, 0, "a".repeat(64))

private fun job(version: Int = 1, token: String = "44444444-4444-4444-8444-444444444444") = GatewayQueueJob(
    jobId = "33333333-3333-4333-8333-333333333333",
    dispatchId = "66666666-6666-4666-8666-666666666666",
    campaignId = "77777777-7777-4777-8777-777777777777",
    sourceRowNumber = 2,
    normalizedE164 = "+923001234567",
    renderedMessage = "Queue test",
    smsEncoding = "GSM-7",
    segmentCount = 1,
    leaseToken = token,
    leaseVersion = version,
    leasedAt = 1_790_000_000_000L + version * 200_000L,
    leaseExpiresAt = 1_790_000_120_000L + version * 200_000L,
    gatewaySimId = simId,
    simSubscriptionId = 11,
    simSlotIndex = 0,
    simIdentityHash = "a".repeat(64)
)

private class Credentials : CredentialStore {
    override fun saveCredential(deviceId: String, credential: String, version: Int, expiresAt: Long, deviceName: String) = true
    override fun getDeviceId() = deviceId
    override fun getDeviceName() = "Phone"
    override fun getCredential() = "credential-12345678901234567890"
    override fun getCredentialVersion() = 1
    override fun getExpiresAt() = Long.MAX_VALUE
    override fun clear() = true
}

private class MemoryStore(private val events: MutableList<String>) : CloudQueueLocalStore {
    data class E(var job: GatewayQueueJob, var acked: Boolean)
    val rows = linkedMapOf<String,E>()
    var fail = false
    var lastSync: Long? = null
    override fun persist(deviceId: String, job: GatewayQueueJob): PersistResult {
        events += "persist"
        if (fail) error("disk failure")
        val e = rows[job.jobId]
        val decision = CloudJobReconciler.decide(e?.let { CloudJobReconciler.StoredShape(it.job,it.acked) }, job)
        when(decision) {
            PersistResult.INSERTED -> rows[job.jobId] = E(job,false)
            PersistResult.SAME_LEASE -> Unit
            PersistResult.NEWER_LEASE -> rows[job.jobId] = E(job,false)
        }
        return decision
    }
    override fun pendingAckReceipts(deviceId: String, limit: Int) = rows.values.filter{!it.acked}.take(limit).map{it.job.receipt()}
    override fun markAcknowledged(deviceId: String, receipts: List<QueueLeaseReceipt>): Int {
        events += "markAck"; var n=0
        receipts.forEach { r -> rows[r.jobId]?.let { e -> if(e.job.receipt()==r){e.acked=true;n++} } }
        return n
    }
    override fun counts(deviceId: String): CloudQueueCounts { val p=rows.values.count{!it.acked}; return CloudQueueCounts(rows.size,p,rows.size-p,lastSync) }
    override fun recordSync(deviceId: String, atMillis: Long, error: String?) { lastSync=atMillis }
    override fun exportDiagnostics(deviceId: String?) = "{}"
}

private class Api(private val events: MutableList<String>) : GatewayQueueApi {
    var jobs: List<GatewayQueueJob> = emptyList()
    var networkOnAck=false
    var staleFirst=false
    var ackCalls=0
    override fun claimGatewayMessageJobs(deviceId:String,credential:String,limit:Int):List<GatewayQueueJob>{events+="claim";return jobs}
    override fun acknowledgeGatewayMessageJobs(deviceId:String,credential:String,receipts:List<QueueLeaseReceipt>):Int{
        events+="ack";ackCalls++
        if(networkOnAck) throw CloudException.NetworkException("offline")
        if(staleFirst && ackCalls==1) throw CloudException.QueueLeaseConflictException()
        return receipts.size
    }
    override fun releaseGatewayMessageJobLeases(deviceId:String,credential:String,receipts:List<QueueLeaseReceipt>):Int{events+="release";return receipts.size}
}

fun main(){
    var assertions=0
    fun ok(v:Boolean,m:String){check(v,m);assertions++}

    val a=job(1); val b=job(2,"55555555-5555-4555-8555-555555555555")
    ok(CloudJobReconciler.decide(null,a)==PersistResult.INSERTED,"insert")
    ok(CloudJobReconciler.decide(CloudJobReconciler.StoredShape(a,false),a)==PersistResult.SAME_LEASE,"same lease")
    ok(CloudJobReconciler.decide(CloudJobReconciler.StoredShape(a,false),b)==PersistResult.NEWER_LEASE,"new lease")
    try { CloudJobReconciler.decide(CloudJobReconciler.StoredShape(a,false),b.copy(renderedMessage="tampered")); error("expected integrity") } catch(_:CloudQueueIntegrityException){assertions++}

    run {
        val ackedShape = CloudJobReconciler.StoredShape(a, true)
        try { CloudJobReconciler.decide(ackedShape, a); error("expected acked same lease reject") } catch(_:CloudQueueIntegrityException){assertions++}
        try { CloudJobReconciler.decide(ackedShape, b); error("expected acked newer lease reject") } catch(_:CloudQueueIntegrityException){assertions++}
    }

    run {
        val ev=mutableListOf<String>(); val store=MemoryStore(ev); val api=Api(ev).apply{jobs=listOf(a)}
        val state=CloudQueueRepository(Credentials(),api,store){1_800_000_000_000L}.sync(identity)
        ok(state is CloudQueueState.Ready,"ready")
        ok(ev==listOf("claim","persist","ack","markAck"),"persist before ack: $ev")
        ok(store.counts(deviceId).acknowledgedJobs==1,"acked")
    }
    run {
        val ev=mutableListOf<String>(); val store=MemoryStore(ev).apply{fail=true}; val api=Api(ev).apply{jobs=listOf(a)}
        val state=CloudQueueRepository(Credentials(),api,store){1_800_000_000_000L}.sync(identity)
        ok(state is CloudQueueState.Error,"persistence error")
        ok("ack" !in ev,"no ack on persistence failure")
        ok("release" in ev,"unpersisted release")
    }
    run {
        val ev=mutableListOf<String>(); val store=MemoryStore(ev); val api=Api(ev).apply{jobs=listOf(a);networkOnAck=true}
        val state=CloudQueueRepository(Credentials(),api,store){1_800_000_000_000L}.sync(identity)
        ok(state is CloudQueueState.Offline,"offline")
        ok(store.counts(deviceId).unacknowledgedJobs==1,"durable pending retained")
    }
    run {
        val ev=mutableListOf<String>(); val store=MemoryStore(ev); store.persist(deviceId,a); ev.clear()
        val api=Api(ev).apply{staleFirst=true;jobs=listOf(b)}
        val state=CloudQueueRepository(Credentials(),api,store){1_800_000_000_000L}.sync(identity)
        ok(state is CloudQueueState.Ready,"re-lease ready")
        ok(store.rows.size==1,"no duplicate")
        ok(store.counts(deviceId).unacknowledgedJobs==0,"new lease acked")
    }
    run {
        val ev=mutableListOf<String>(); val store=MemoryStore(ev); val api=Api(ev).apply{jobs=listOf(a.copy(simSubscriptionId=22,simSlotIndex=1,simIdentityHash="b".repeat(64)))}
        val state=CloudQueueRepository(Credentials(),api,store){1_800_000_000_000L}.sync(identity)
        ok(state is CloudQueueState.Error,"sim mismatch error")
        ok("persist" !in ev && "ack" !in ev,"no persist/ack on sim mismatch")
    }
    println("PASS: $assertions queue core assertions")
}
