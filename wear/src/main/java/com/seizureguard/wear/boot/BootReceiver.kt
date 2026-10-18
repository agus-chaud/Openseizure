package com.seizureguard.wear.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.seizureguard.wear.schedule.MonitoringSchedule
import com.seizureguard.wear.schedule.MonitoringScheduler
import com.seizureguard.wear.service.MonitorPrefs
import com.seizureguard.wear.service.RestartNeededNotifier
import java.time.Instant
import java.time.ZoneId

/** Only resume what the user had running; a fresh install or a deliberate stop stays off. */
fun shouldResume(wasMonitoring: Boolean): Boolean = wasMonitoring

/**
 * DEC-072: after a boot or an app update, monitoring starts when the clock is inside the night window
 * (22:00-10:00), unless the user stopped it manually during this night ([manualStopUntilMs] still in the future).
 * Outside the window it does not start, whatever `was_monitoring` says.
 */
fun shouldStartAtBoot(inNightWindow: Boolean, manualStopUntilMs: Long, nowMs: Long): Boolean =
    inNightWindow && manualStopUntilMs <= nowMs

/**
 * Re-arms the daily alarms and, inside the night window, resumes monitoring after the watch reboots (battery died,
 * crash, OS update) or after the app is updated (DEC-069, DEC-072). If the OS refuses the start, it degrades to a
 * SILENT notification (DEC-057) instead of failing without a trace. Note the refusal that matters most
 * (startForeground of a health service) is thrown inside the service and handled there.
 * The phone's resume request uses [resumeMonitoringIfWanted] instead (it keeps the `was_monitoring` rule).
 */
class BootReceiver : BroadcastReceiver() {

    internal var starter: (Context) -> Unit = defaultMonitoringStarter
    internal var clock: () -> Instant = { Instant.now() }
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { MonitoringScheduler(context, clock, zone).armAll() }
            .onFailure { Log.e(TAG, "Could not arm the schedule ($action)", it) }
        val now = clock()
        val inWindow = MonitoringSchedule.isInNightWindow(now, zone())
        val manualStopUntil = MonitorPrefs.manualStopUntilMs(context)
        if (!shouldStartAtBoot(inWindow, manualStopUntil, now.toEpochMilli())) {
            Log.i(TAG, "Not starting ($action): inNightWindow=$inWindow, manualStopUntil=$manualStopUntil")
            return
        }
        MonitorPrefs.setWasMonitoring(context, true) // the intent to monitor, for sticky restarts and the phone request
        try {
            starter(context)
            Log.i(TAG, "Monitoring start requested ($action)")
        } catch (e: Exception) { // ForegroundServiceStartNotAllowedException / SecurityException / anything else
            Log.e(TAG, "Could not start monitoring ($action)", e)
            RestartNeededNotifier.post(context)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
