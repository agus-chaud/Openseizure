package com.seizureguard.wear.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.seizureguard.wear.service.MonitorPrefs
import com.seizureguard.wear.service.RestartNeededNotifier
import com.seizureguard.wear.service.SeizureMonitorService

/** Only resume what the user had running; a fresh install or a deliberate stop stays off. */
fun shouldResume(wasMonitoring: Boolean): Boolean = wasMonitoring

/**
 * Resumes monitoring after the watch reboots (battery died, crash, OS update) or after the app is
 * updated, if the user had left it on. If the OS refuses the start, it degrades to a SILENT
 * notification (DEC-057) instead of failing without a trace. Note the refusal that matters most
 * (startForeground of a health service) is thrown inside the service and handled there.
 */
class BootReceiver : BroadcastReceiver() {

    internal var starter: (Context) -> Unit = { context ->
        ContextCompat.startForegroundService(context, SeizureMonitorService.startIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!shouldResume(MonitorPrefs.wasMonitoring(context))) return
        try {
            starter(context)
        } catch (e: Exception) { // ForegroundServiceStartNotAllowedException / SecurityException / anything else
            Log.e(TAG, "Could not resume monitoring from $action", e)
            RestartNeededNotifier.post(context)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
