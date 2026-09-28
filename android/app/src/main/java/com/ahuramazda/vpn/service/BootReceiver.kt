package com.ahuramazda.vpn.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ahuramazda.vpn.core.Log
import com.ahuramazda.vpn.core.Prefs

/** Brings the tunnel back after a reboot when the user asked for it. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (!prefs.autoStartOnBoot || prefs.activeProfile().host.isEmpty()) return
        Log.i("auto-connecting after $action")
        try {
            AhuraVpnService.start(context)
        } catch (e: Exception) {
            Log.e("auto-connect failed", e)
        }
    }
}
