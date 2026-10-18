package com.seizureguard.wear.schedule

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.seizureguard.wear.R
import com.seizureguard.wear.service.RestartNeededNotifier
import com.seizureguard.wear.service.SeizureMonitorService
import java.time.Instant
import java.time.ZoneId

/**
 * Arms the two daily alarms (DEC-072): START at 22:00 and STOP at 10:00 local. Idempotent: re-arming replaces the
 * pending alarm of the same action, so it is safe (and required) to call after boot, app update, time change,
 * timezone change, an exact-alarm permission change, and after every alarm fires.
 *
 * Exact alarms use `setExactAndAllowWhileIdle` (Doze-safe; the alarms page documents exact alarms as not affected by
 * foreground-service launch restrictions). If exact alarms are not allowed it falls back to `setAndAllowWhileIdle`
 * (inexact, may run late) and posts ONE silent notice (DEC-057) saying so.
 */
class MonitoringScheduler(
    private val context: Context,
    private val clock: () -> Instant = { Instant.now() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** @return true when both alarms were armed as exact. Never throws. */
    fun armAll(): Boolean {
        val now = clock()
        val z = zone()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val startAt = MonitoringSchedule.nextStart(now, z).toEpochMilli()
        val stopAt = MonitoringSchedule.nextStop(now, z).toEpochMilli()
        val exactOk = canScheduleExact(alarmManager)
        val startExact = arm(alarmManager, ACTION_MONITOR_START, REQUEST_START, startAt, exactOk)
        val stopExact = arm(alarmManager, ACTION_MONITOR_STOP, REQUEST_STOP, stopAt, exactOk)
        val exact = startExact && stopExact
        Log.i(TAG, "Alarms armed: start=$startAt stop=$stopAt exact=$exact")
        if (exact) {
            context.getSystemService(NotificationManager::class.java)
                .cancel(SeizureMonitorService.SCHEDULE_INEXACT_NOTIFICATION_ID)
        } else {
            RestartNeededNotifier.post(
                context,
                textRes = R.string.notification_schedule_inexact_text,
                titleRes = R.string.notification_schedule_inexact_title,
                notificationId = SeizureMonitorService.SCHEDULE_INEXACT_NOTIFICATION_ID,
            )
        }
        return exact
    }

    private fun canScheduleExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    /** @return true when armed as exact; false when it fell back to inexact (or could not be armed at all). */
    private fun arm(am: AlarmManager, action: String, requestCode: Int, atMs: Long, exactOk: Boolean): Boolean {
        val pi = pendingIntent(context, action, requestCode)
        if (exactOk) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                return true
            } catch (e: SecurityException) { // permission revoked between the check and the call
                Log.w(TAG, "Exact alarm refused for $action; falling back to inexact", e)
            }
        }
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } catch (e: Exception) {
            Log.e(TAG, "Could not arm $action at all", e)
        }
        return false
    }

    companion object {
        private const val TAG = "MonitoringScheduler"
        const val ACTION_MONITOR_START = "com.seizureguard.wear.SCHEDULE_MONITOR_START"
        const val ACTION_MONITOR_STOP = "com.seizureguard.wear.SCHEDULE_MONITOR_STOP"
        private const val REQUEST_START = 7201
        private const val REQUEST_STOP = 7202

        internal fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, ScheduleAlarmReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
