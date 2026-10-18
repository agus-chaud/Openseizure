package com.seizureguard.wear.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-arms both alarms after a clock, timezone or exact-alarm-permission change (DEC-072). Exported because the
 * system sends these broadcasts; it only re-arms, so a foreign sender gains nothing.
 */
class ScheduleRearmReceiver : BroadcastReceiver() {

    internal var scheduler: (Context) -> Unit = { MonitoringScheduler(it).armAll() }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            ACTION_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                Log.i(TAG, "Re-arming the schedule (${intent.action})")
                runCatching { scheduler(context) }.onFailure { Log.e(TAG, "Could not re-arm the schedule", it) }
            }
        }
    }

    companion object {
        private const val TAG = "ScheduleRearmReceiver"

        /** `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (API 31), as a literal for minSdk 30. */
        const val ACTION_EXACT_ALARM_PERMISSION_STATE_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
