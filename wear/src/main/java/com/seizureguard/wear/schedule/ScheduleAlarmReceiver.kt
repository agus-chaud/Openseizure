package com.seizureguard.wear.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.seizureguard.wear.boot.defaultMonitoringStarter
import com.seizureguard.wear.service.MonitorPrefs
import com.seizureguard.wear.service.RestartNeededNotifier
import com.seizureguard.wear.service.SeizureMonitorService
import java.time.Instant
import java.time.ZoneId

/**
 * The two daily alarms (DEC-072). Not exported: only this app's own PendingIntents reach it, so no other app can
 * stop monitoring by broadcasting the STOP action.
 *
 *  - START (22:00): clears any manual stop from the previous night, records the intent to monitor (so the DEC-070
 *    phone resume request and a sticky restart work if the start fails) and starts through the DEC-069 path. A
 *    refused start degrades to the silent restart-needed notice.
 *  - STOP (10:00): stops monitoring as a scheduled pause (`/osd/monitoring_paused` with the next start), not as a
 *    deliberate stop. If the service call itself is refused, monitoring keeps running (the safe direction).
 *
 * The next alarm is always re-armed first, so a failure below cannot end the schedule.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    internal var starter: (Context) -> Unit = defaultMonitoringStarter
    internal var stopper: (Context, Long) -> Unit = { ctx, nextStartMs ->
        ctx.startService(SeizureMonitorService.stopIntent(ctx, pauseUntilMs = nextStartMs))
    }
    internal var pausedNotifier: (Context, Long) -> Unit = MonitoringPausedMessage::send
    internal var isServiceMonitoring: () -> Boolean = { SeizureMonitorService.monitoring.value }
    internal var clock: () -> Instant = { Instant.now() }
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != MonitoringScheduler.ACTION_MONITOR_START && action != MonitoringScheduler.ACTION_MONITOR_STOP) return
        runCatching { MonitoringScheduler(context, clock, zone).armAll() }
            .onFailure { Log.e(TAG, "Could not re-arm the schedule", it) }
        try {
            if (action == MonitoringScheduler.ACTION_MONITOR_START) onStart(context) else onStop(context)
        } catch (e: Exception) { // never take the process down from an alarm
            Log.e(TAG, "Scheduled $action failed unexpectedly", e)
        }
    }

    private fun onStart(context: Context) {
        Log.i(TAG, "22:00 alarm: starting monitoring")
        MonitorPrefs.setManualStopUntilMs(context, 0L)
        MonitorPrefs.setWasMonitoring(context, true)
        try {
            starter(context)
        } catch (e: Exception) { // ForegroundServiceStartNotAllowedException / SecurityException / anything else
            Log.e(TAG, "Scheduled start refused", e)
            RestartNeededNotifier.post(context)
        }
    }

    private fun onStop(context: Context) {
        val nextStartMs = MonitoringSchedule.nextStart(clock(), zone()).toEpochMilli()
        Log.i(TAG, "10:00 alarm: pausing until $nextStartMs")
        if (isServiceMonitoring()) {
            try {
                stopper(context, nextStartMs) // the service sends the paused message after clearing was_monitoring
            } catch (e: Exception) {
                Log.e(TAG, "Could not stop the service; monitoring keeps running", e)
            }
        } else {
            MonitorPrefs.setWasMonitoring(context, false)
            pausedNotifier(context, nextStartMs)
        }
    }

    private companion object {
        const val TAG = "ScheduleAlarmReceiver"
    }
}
