package com.bulktext.gateway
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*

/** User-started, bounded short session. Never starts on boot, never automatically retries. */
class SendingService : Service() {
    private val handler=Handler(Looper.getMainLooper())
    private val store get()=(application as GatewayApp).store
    private var session: Session?=null
    private var active: String?=null
    private var submittedAt=0L
    private var startedAt=0L
    private var wake: PowerManager.WakeLock?=null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="STOP") { finish("PAUSED"); return START_NOT_STICKY }
        if(session!=null) return START_NOT_STICKY
        val selected=store.latest()
        if(selected==null || selected.id!=intent?.getStringExtra("session")) {stopSelf();return START_NOT_STICKY}
        try {
            showNotification()
            Telephony.check(this,selected)
            if(!store.start(selected.id)) {finish("PAUSED");return START_NOT_STICKY}
            session=selected
            startedAt=SystemClock.elapsedRealtime()
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"BulkText:TestSession").apply {acquire(145_000)}
            handler.post(tick)
        } catch(e: Exception) {
            store.annotate(selected.id,"Cannot start: ${e.message ?: e.javaClass.simpleName}")
            store.stop(selected.id,"PAUSED")
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun showNotification() {
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sending","Test SMS session",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,SendingService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(this,"sending").setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("BulkText test session").setContentText("Using your selected SIM • Tap Stop to pause")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Stop",stop).build()).build()
        if(Build.VERSION.SDK_INT>=34) startForeground(7,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE) else startForeground(7,n)
    }
    private val tick=object: Runnable {
        override fun run() {
            val s=session ?: return
            if(store.status(s.id)!="RUNNING") {finish("PAUSED");return}
            if(SystemClock.elapsedRealtime()-startedAt>140_000) {finish("PAUSED");return}
            try {
                Telephony.check(this@SendingService,s)
                val current=active
                if(current!=null) {
                    val state=store.jobs(s.id).firstOrNull{it.id==current}?.status
                    if(state=="SUBMITTING" && SystemClock.elapsedRealtime()-submittedAt>25_000) {
                        store.unknown(current,"No sent callback within 25s; no automatic retry")
                        finish("PAUSED");return
                    }
                    if(state in listOf("FAILED","PARTIAL","UNKNOWN")) {finish("PAUSED");return}
                    if(state=="SUBMITTING" || SystemClock.elapsedRealtime()-submittedAt<10_000) {handler.postDelayed(this,500);return}
                    active=null
                }
                val job=store.claim(s.id)
                if(job==null) {finish("COMPLETE");return}
                active=job.id; submittedAt=SystemClock.elapsedRealtime()
                val manager=Telephony.manager(this@SendingService,s.sub)
                val parts=manager.divideMessage(job.body)
                check(parts.size==job.parts) { "Segment calculation changed; submission withheld" }
                val sent=ArrayList<PendingIntent>();val delivered=ArrayList<PendingIntent>()
                parts.indices.forEach {i -> sent.add(callback(job.id,i,"sent"));delivered.add(callback(job.id,i,"delivery"))}
                manager.sendMultipartTextMessage(job.phone,null,parts,sent,delivered)
                handler.postDelayed(this,500)
            } catch(e: Exception) {
                store.annotate(s.id,"Sending paused: ${e.message ?: e.javaClass.simpleName}")
                active?.let{store.unknown(it,"Submission interrupted (${e.javaClass.simpleName}); review before any new send")}
                finish("PAUSED")
            }
        }
    }
    private fun callback(job: String,part: Int,kind: String): PendingIntent {
        val i=Intent(this,SmsResultReceiver::class.java).setData(Uri.parse("bulktext://callback/$job/$part/$kind"))
            .putExtra("job",job).putExtra("part",part).putExtra("kind",kind)
        return PendingIntent.getBroadcast(this,0,i,PendingIntent.FLAG_IMMUTABLE)
    }
    private fun finish(status: String) {
        handler.removeCallbacksAndMessages(null)
        active?.let {store.unknown(it,"Session stopped before all sent callbacks; never auto-retry")}
        session?.let {store.stop(it.id,status)}
        session=null;active=null
        if(wake?.isHeld==true) wake?.release()
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onTimeout(startId: Int) {finish("PAUSED")}
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        active?.let {store.unknown(it,"Service destroyed during submission")}
        session?.let {store.stop(it.id,"PAUSED")}
        if(wake?.isHeld==true) wake?.release()
        super.onDestroy()
    }
}
