package com.bulktext.gateway
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val job=intent.getStringExtra("job") ?: return
        val part=intent.getIntExtra("part",-1)
        val kind=intent.getStringExtra("kind") ?: return
        if(part<0) return
        (context.applicationContext as GatewayApp).store.callback(job,part,kind,resultCode==Activity.RESULT_OK,resultCode)
    }
}
