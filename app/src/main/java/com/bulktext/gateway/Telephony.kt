package com.bulktext.gateway
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager

object Telephony {
    fun permitted(c: Context)=c.checkSelfPermission(Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED && c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED
    fun sims(c: Context): List<SubscriptionInfo> = if(c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED) c.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty() else emptyList()
    // Subscription ID, slot and carrier identity: no phone-number/ICCID harvesting.
    fun key(s: SubscriptionInfo)="${s.subscriptionId}:${s.simSlotIndex}:${if(Build.VERSION.SDK_INT>=29) s.carrierId else -1}:${s.carrierName}"
    fun manager(c: Context,sub: Int): SmsManager = if(Build.VERSION.SDK_INT>=31) requireNotNull(c.getSystemService(SmsManager::class.java)).createForSubscriptionId(sub) else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(sub)
    fun check(c: Context,s: Session) {
        check(permitted(c)) { "SMS/phone permission missing. Grant permission and resume manually." }
        val current=sims(c).firstOrNull {it.subscriptionId==s.sub}
        check(current!=null && key(current)==s.simKey) { "Selected SIM changed or is unavailable. Cancel this session and confirm a new one." }
    }
}
