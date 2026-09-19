package com.seizureguard.phone.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.seizureguard.phone.bridge.BridgeNotifications
import com.seizureguard.phone.bridge.BridgePrefs
import com.seizureguard.phone.bridge.OsdBridgeService
import com.seizureguard.phone.summary.MorningSummaryReceiver

/** Only resume what the user had running; a fresh install or a deliberate stop stays off. */
fun shouldResume(wasBridging: Boolean): Boolean = wasBridging

/** Resumes the bridge after a reboot; if the OS refuses, alerts loudly instead of failing silently. */
class BootReceiver : BroadcastReceiver() {

    internal var starter: (Context) -> Unit = OsdBridgeService::start

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!shouldResume(BridgePrefs.wasBridging(context))) return
        MorningSummaryReceiver.arm(context)
        try {
            starter(context)
        } catch (e: Exception) { // ForegroundServiceStartNotAllowedException / SecurityException / anything else
            Log.e(TAG, "Could not resume the bridge from boot", e)
            BridgeNotifications.postRestartNeeded(context)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
