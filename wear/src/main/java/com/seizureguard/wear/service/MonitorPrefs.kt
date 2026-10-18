package com.seizureguard.wear.service

import android.content.Context

/**
 * Persists whether the user left monitoring on, so a sticky restart (null intent) and the boot
 * receiver know whether to resume it. Only an explicit ACTION_STOP clears it.
 *
 * Same file and key the service has always used: no migration for installed watches.
 */
object MonitorPrefs {
    const val PREFS_NAME = "seizure_monitor_prefs"
    const val KEY_WAS_MONITORING = "was_monitoring"

    fun wasMonitoring(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WAS_MONITORING, false)

    fun setWasMonitoring(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WAS_MONITORING, value)
            .apply()
    }

    /**
     * Epoch ms until which a MANUAL stop holds (DEC-072): the next 22:00 after the stop. While it is in the future a
     * boot or app update inside the night window does not restart monitoring; the 22:00 alarm clears it, and any
     * start (manual or scheduled) clears it. 0 = no manual stop pending.
     */
    fun manualStopUntilMs(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_MANUAL_STOP_UNTIL_MS, 0L)

    fun setManualStopUntilMs(context: Context, untilMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_MANUAL_STOP_UNTIL_MS, untilMs)
            .apply()
    }

    const val KEY_MANUAL_STOP_UNTIL_MS = "manual_stop_until_ms"
}
