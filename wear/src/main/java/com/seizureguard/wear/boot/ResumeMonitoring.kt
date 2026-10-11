package com.seizureguard.wear.boot

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.seizureguard.wear.R
import com.seizureguard.wear.service.MonitorPrefs
import com.seizureguard.wear.service.RestartNeededNotifier
import com.seizureguard.wear.service.SeizureMonitorService

private const val TAG = "ResumeMonitoring"

/** The DEC-069 start path: a foreground-service start of [SeizureMonitorService] with ACTION_START. */
internal val defaultMonitoringStarter: (Context) -> Unit = { context ->
    ContextCompat.startForegroundService(context, SeizureMonitorService.startIntent(context))
}

/**
 * Shared by [BootReceiver] (reboot or app update) and the phone's resume request (DEC-070): start monitoring only
 * if the user had left it on ([MonitorPrefs.wasMonitoring]); a deliberate stop or a fresh install stays off. If the
 * OS refuses the start it degrades to the SILENT restart-needed notice (DEC-057) instead of crashing. Starting an
 * already-running service is harmless: ACTION_START is idempotent in [SeizureMonitorService].
 *
 * @param noticeTextRes wording of the notice when the start is refused (what the user needs to know differs by path).
 * @return true when a start was attempted and accepted synchronously.
 */
internal fun resumeMonitoringIfWanted(
    context: Context,
    reason: String,
    starter: (Context) -> Unit = defaultMonitoringStarter,
    noticeTextRes: Int = R.string.notification_restart_needed_text,
): Boolean {
    if (!shouldResume(MonitorPrefs.wasMonitoring(context))) {
        Log.i(TAG, "Not resuming ($reason): the user did not leave monitoring on")
        return false
    }
    return try {
        starter(context)
        Log.i(TAG, "Resume requested ($reason)")
        true
    } catch (e: Exception) { // ForegroundServiceStartNotAllowedException / SecurityException / anything else
        Log.e(TAG, "Could not resume monitoring ($reason)", e)
        RestartNeededNotifier.post(context, noticeTextRes)
        false
    }
}
