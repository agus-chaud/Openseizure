package com.seizureguard.phone.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.seizureguard.phone.R

/**
 * Minimal notifications the FGS needs in Batch 5a. Batch 5b's `BridgeNotifications` replaces this
 * (same channel ids, per design) and adds the 60 s fault re-post.
 */
internal object BridgeStatusNotification {
    const val STATUS_CHANNEL_ID = "osd_bridge_status"
    const val FAULT_CHANNEL_ID = "osd_bridge_fault"
    const val STATUS_NOTIFICATION_ID = 4101
    private const val START_FAILURE_NOTIFICATION_ID = 4102
    private const val TAG = "BridgeStatusNotif"

    fun buildStatus(context: Context): Notification {
        nm(context).createNotificationChannel(
            NotificationChannel(
                STATUS_CHANNEL_ID, context.getString(R.string.bridge_status_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        return NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.bridge_status_title))
            .setContentText(context.getString(R.string.bridge_status_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Loud, best-effort "the bridge could not start" alert (dropped by the OS if notifications are denied). */
    fun postStartFailure(context: Context) {
        try {
            val nm = nm(context)
            nm.createNotificationChannel(
                NotificationChannel(
                    FAULT_CHANNEL_ID, context.getString(R.string.bridge_fault_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
            nm.notify(
                START_FAILURE_NOTIFICATION_ID,
                NotificationCompat.Builder(context, FAULT_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(context.getString(R.string.bridge_start_failed_title))
                    .setContentText(context.getString(R.string.bridge_start_failed_text))
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOngoing(true)
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not post start-failure notification", e)
        }
    }

    private fun nm(context: Context) = context.getSystemService(NotificationManager::class.java)
}
