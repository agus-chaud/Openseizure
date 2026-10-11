package com.seizureguard.wear.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Only resume what the user had running; a fresh install or a deliberate stop stays off. */
fun shouldResume(wasMonitoring: Boolean): Boolean = wasMonitoring

/**
 * Resumes monitoring after the watch reboots (battery died, crash, OS update) or after the app is
 * updated, if the user had left it on. If the OS refuses the start, it degrades to a SILENT
 * notification (DEC-057) instead of failing without a trace. Note the refusal that matters most
 * (startForeground of a health service) is thrown inside the service and handled there.
 * The start path is shared with the phone's resume request: see [resumeMonitoringIfWanted].
 */
class BootReceiver : BroadcastReceiver() {

    internal var starter: (Context) -> Unit = defaultMonitoringStarter

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        resumeMonitoringIfWanted(context, action, starter)
    }
}
