package com.seizureguard.phone.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.seizureguard.phone.R
import com.seizureguard.phone.setup.SetupActivity

const val FAULT_REPOST_MS = 60_000L

enum class FaultAction { NONE, POST, CLEAR }

/** POST on a new/changed fault and every [FAULT_REPOST_MS] while it stands; CLEAR once it is gone. */
fun faultAction(fault: BridgeFault, shown: BridgeFault, lastPostAtMs: Long, nowMs: Long): FaultAction = when {
    fault == BridgeFault.NONE -> if (shown != BridgeFault.NONE) FaultAction.CLEAR else FaultAction.NONE
    fault != shown || nowMs - lastPostAtMs >= FAULT_REPOST_MS -> FaultAction.POST
    else -> FaultAction.NONE
}

/** Status (LOW, required by the FGS) and fault (HIGH, re-posted) notifications. Health ticks come from one coroutine. */
internal class BridgeNotifications(
    private val context: Context,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var shown = BridgeFault.NONE
    private var lastPostAtMs = 0L

    fun onHealthTick(fault: BridgeFault) {
        val now = nowMs()
        when (faultAction(fault, shown, lastPostAtMs, now)) {
            FaultAction.POST -> {
                notifySafely(context, FAULT_NOTIFICATION_ID, faultNotification(context, faultTextRes(fault)))
                shown = fault
                lastPostAtMs = now
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
        const val FAULT_CHANNEL_ID = "osd_bridge_fault"
        const val STATUS_NOTIFICATION_ID = 4101
        const val START_FAILURE_NOTIFICATION_ID = 4102
        const val FAULT_NOTIFICATION_ID = 4103
        private const val TAG = "BridgeNotifications"

        fun faultTextRes(fault: BridgeFault): Int = when (fault) {
            BridgeFault.NO_WATCH_DATA -> R.string.fault_no_watch_data
            BridgeFault.OSD_UNREACHABLE -> R.string.fault_osd_unreachable
            BridgeFault.OSD_WRONG_DATASOURCE -> R.string.fault_osd_wrong_datasource
            BridgeFault.OSD_REJECTS_DATA -> R.string.fault_osd_rejects_data
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

        /** Loud, best-effort "the bridge could not start" alert (dropped by the OS if notifications are denied). */
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

        private fun faultNotification(context: Context, textRes: Int, contentIntent: PendingIntent? = null): Notification {
            nm(context).createNotificationChannel(
                NotificationChannel(
                    FAULT_CHANNEL_ID, context.getString(R.string.bridge_fault_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
            val text = context.getString(textRes)
            return NotificationCompat.Builder(context, FAULT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.bridge_start_failed_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(false) // each 60 s re-post must sound + vibrate again
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
