package com.seizureguard.wear.boot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.seizureguard.wear.MainActivity
import com.seizureguard.wear.R
import com.seizureguard.wear.service.MonitorPrefs
import com.seizureguard.wear.service.SeizureMonitorService

/** Only resume what the user had running; a fresh install or a deliberate stop stays off. */
fun shouldResume(wasMonitoring: Boolean): Boolean = wasMonitoring

/**
 * Resumes monitoring after the watch reboots (battery died, crash, OS update) or after the app is
 * updated, if the user had left it on. If the OS refuses, it degrades to a SILENT notification
 * (DEC-057: faults never vibrate or sound) instead of failing without a trace.
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
            postRestartNeeded(context)
        }
    }

    private fun postRestartNeeded(context: Context) {
        try {
            SeizureMonitorService.ensureNotificationChannel(context)
            val notification = NotificationCompat.Builder(context, SeizureMonitorService.CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_restart_needed_title))
                .setContentText(context.getString(R.string.notification_restart_needed_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setSilent(true)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, 0,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
            context.getSystemService(android.app.NotificationManager::class.java)
                .notify(SeizureMonitorService.RESTART_NEEDED_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Last resort: a receiver must never crash the process at boot.
            Log.e(TAG, "Could not post the restart-needed notification", e)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
