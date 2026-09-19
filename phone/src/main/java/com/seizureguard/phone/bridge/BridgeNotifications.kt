package com.seizureguard.phone.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.seizureguard.phone.R
import com.seizureguard.phone.setup.SetupActivity

enum class FaultAction { NONE, POST, CLEAR }

/** POST only when the fault appears or changes (never on a timer); CLEAR once it is gone (DEC-057). */
fun faultAction(fault: BridgeFault, shown: BridgeFault): FaultAction = when {
    fault == BridgeFault.NONE -> if (shown != BridgeFault.NONE) FaultAction.CLEAR else FaultAction.NONE
    fault != shown -> FaultAction.POST
    else -> FaultAction.NONE
}

/** Status (LOW, required by the FGS) and fault (silent, passive) notifications. Health ticks come from one coroutine. */
internal class BridgeNotifications(private val context: Context) {
    private var shown = BridgeFault.NONE

    fun onHealthTick(fault: BridgeFault) {
        when (faultAction(fault, shown)) {
            FaultAction.POST -> {
                notifySafely(context, FAULT_NOTIFICATION_ID, faultNotification(context, faultTextRes(fault)))
                shown = fault
            }
            FaultAction.CLEAR -> {
                try {
                    nm(context).cancel(FAULT_NOTIFICATION_ID)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not clear fault notification", e)
                }
                shown = BridgeFault.NONE
            }
            FaultAction.NONE -> Unit
        }
    }

    companion object {
        const val STATUS_CHANNEL_ID = "osd_bridge_status"
        const val FAULT_CHANNEL_ID = "osd_bridge_fault_silent"
        const val LEGACY_FAULT_CHANNEL_ID = "osd_bridge_fault" // HIGH; importance cannot be lowered, so it is deleted
        const val STATUS_NOTIFICATION_ID = 4101
        const val START_FAILURE_NOTIFICATION_ID = 4102
        const val FAULT_NOTIFICATION_ID = 4103
        const val SUMMARY_CHANNEL_ID = "osd_bridge_summary"
        const val SUMMARY_NOTIFICATION_ID = 4104
        private const val TAG = "BridgeNotifications"

        fun faultTextRes(fault: BridgeFault): Int = when (fault) {
            BridgeFault.NO_WATCH_DATA -> R.string.fault_no_watch_data
            BridgeFault.OSD_UNREACHABLE -> R.string.fault_osd_unreachable
            BridgeFault.OSD_WRONG_DATASOURCE -> R.string.fault_osd_wrong_datasource
            BridgeFault.OSD_REJECTS_DATA -> R.string.fault_osd_rejects_data
            BridgeFault.OSD_DATA_STALE -> R.string.fault_osd_data_stale
            BridgeFault.NONE -> R.string.bridge_status_text
        }

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

        /** Silent, best-effort "the bridge could not start" notice (dropped by the OS if notifications are denied). */
        fun postStartFailure(context: Context) = notifySafely(
            context, START_FAILURE_NOTIFICATION_ID, faultNotification(context, R.string.bridge_start_failed_text),
        )

        /** Boot could not restart the bridge: tap opens setup so the user can start it again. */
        fun postRestartNeeded(context: Context) {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            notifySafely(context, START_FAILURE_NOTIFICATION_ID, faultNotification(context, R.string.bridge_restart_needed_text, open))
        }

        /** One silent, dismissible "last night" summary (LOW channel, no sound or vibration). */
        fun postSummary(context: Context, text: String) {
            nm(context).createNotificationChannel(
                NotificationChannel(
                    SUMMARY_CHANNEL_ID, context.getString(R.string.bridge_summary_channel), NotificationManager.IMPORTANCE_LOW,
                ).apply { setSound(null, null); enableVibration(false); enableLights(false); setShowBadge(false) }
            )
            notifySafely(
                context, SUMMARY_NOTIFICATION_ID,
                NotificationCompat.Builder(context, SUMMARY_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(context.getString(R.string.summary_title))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setSilent(true)
                    .setAutoCancel(true)
                    .build(),
            )
        }

        private fun faultNotification(context: Context, textRes: Int, contentIntent: PendingIntent? = null): Notification {
            val manager = nm(context)
            manager.deleteNotificationChannel(LEGACY_FAULT_CHANNEL_ID)
            manager.createNotificationChannel(
                NotificationChannel(
                    FAULT_CHANNEL_ID, context.getString(R.string.bridge_fault_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                }
            )
            val text = context.getString(textRes)
            return NotificationCompat.Builder(context, FAULT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.bridge_start_failed_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build()
        }

        private fun notifySafely(context: Context, id: Int, n: Notification) {
            try {
                nm(context).notify(id, n)
            } catch (e: Exception) {
                Log.e(TAG, "Could not post notification $id", e)
            }
        }

        private fun nm(context: Context) = context.getSystemService(NotificationManager::class.java)
    }
}
