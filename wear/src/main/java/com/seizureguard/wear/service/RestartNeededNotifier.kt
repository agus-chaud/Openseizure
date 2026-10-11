package com.seizureguard.wear.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.seizureguard.wear.MainActivity
import com.seizureguard.wear.R

/**
 * Silent "monitoring did not restart" notice (DEC-057: faults never sound or vibrate). Tapping it
 * opens [MainActivity], where the user can start monitoring from the foreground. Used by the boot
 * receiver (start refused) and by the service itself (startForeground refused, which is where the
 * system actually throws for a boot-started health foreground service).
 */
object RestartNeededNotifier {
    private const val TAG = "RestartNeededNotifier"

    fun post(context: Context) {
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
            context.getSystemService(NotificationManager::class.java)
                .notify(SeizureMonitorService.RESTART_NEEDED_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Last resort: this runs at boot and must never take the process down.
            Log.e(TAG, "Could not post the restart-needed notification", e)
        }
    }
}
