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
}
