package com.seizureguard.wear.boot

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.seizureguard.wear.R
import com.seizureguard.wear.schedule.MonitoringSchedule
import com.seizureguard.wear.service.MonitorPrefs
import com.seizureguard.wear.service.RestartNeededNotifier
import com.seizureguard.wear.service.SeizureMonitorService
import java.time.Instant
import java.time.ZoneId

private const val TAG = "ResumeMonitoring"

/** The DEC-069 start path: a foreground-service start of [SeizureMonitorService] with ACTION_START. */
internal val defaultMonitoringStarter: (Context) -> Unit = { context ->
    ContextCompat.startForegroundService(context, SeizureMonitorService.startIntent(context))
}

/**
 * Used by the phone's resume request (DEC-070): start monitoring if the user had left it on
 * ([MonitorPrefs.wasMonitoring]) OR (DEC-072) it is inside the 22:00-10:00 night window with no manual stop pending
 * for this night (same rule as [shouldStartAtBoot]), so the retry still works when the 22:00 alarm was missed.
 * A deliberate stop, or daytime with the flag off, stays off. If the
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
    clock: () -> Instant = { Instant.now() },
    zone: () -> ZoneId = { ZoneId.systemDefault() },
): Boolean {
    val now = clock()
    val missedNight = shouldStartAtBoot(
        MonitoringSchedule.isInNightWindow(now, zone()), MonitorPrefs.manualStopUntilMs(context), now.toEpochMilli()
    )
    if (!shouldResume(MonitorPrefs.wasMonitoring(context)) && !missedNight) {
        Log.i(TAG, "Not resuming ($reason): monitoring was not left on and this is not an unstopped night")
        return false
    }
    // DEC-072: a night whose 22:00 alarm never fired (was_monitoring is false after the 10:00 scheduled stop) is still
    // wanted: record the intent, as the 22:00 alarm does, so sticky restarts keep it.
    MonitorPrefs.setWasMonitoring(context, true)
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
