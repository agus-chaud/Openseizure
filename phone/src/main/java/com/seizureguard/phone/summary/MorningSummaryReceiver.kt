package com.seizureguard.phone.summary

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.seizureguard.phone.R
import com.seizureguard.phone.bridge.BridgeHistory
import com.seizureguard.phone.bridge.BridgeNotifications
import com.seizureguard.phone.bridge.BridgePrefs
import com.seizureguard.phone.bridge.FaultKind
import com.seizureguard.phone.bridge.FaultLog
import com.seizureguard.phone.bridge.ServiceLiveness
import com.seizureguard.phone.bridge.serviceDownPeriod

/** Fires once a day, independent of the bridge service, and posts one silent "last night" summary. */
class MorningSummaryReceiver : BroadcastReceiver() {

    internal var history: (Context) -> Pair<FaultLog, ServiceLiveness> = BridgeHistory::of
    internal var nowMs: () -> Long = System::currentTimeMillis

    override fun onReceive(context: Context, intent: Intent) {
        val bridging = BridgePrefs.wasBridging(context)
        if (!bridging) return // user stopped the bridge: no summary and the daily alarm is not re-armed
        arm(context, nowMs()) // re-arm first so a failure below never ends the chain
        try {
            val now = nowMs()
            val (log, liveness) = history(context)
            val down = serviceDownPeriod(liveness.lastAliveMs(), now, liveness.isCleanStop(), bridging)
            val data = buildSummary(withServiceDown(log.periods(), down), now - SUMMARY_WINDOW_MS, now)
            BridgeNotifications.postSummary(context, summaryText(context, data))
        } catch (e: Exception) {
            Log.e(TAG, "Could not post the morning summary", e)
        }
    }

    companion object {
        private const val TAG = "MorningSummary"

        /** Inexact and Doze-tolerant on purpose: no SCHEDULE_EXACT_ALARM permission. */
        fun arm(context: Context, nowMs: Long = System.currentTimeMillis()) {
            try {
                val pi = PendingIntent.getBroadcast(
                    context, 0, Intent(context, MorningSummaryReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                context.getSystemService(AlarmManager::class.java)
                    .setAndAllowWhileIdle(AlarmManager.RTC, nextSummaryTriggerMs(nowMs), pi)
            } catch (e: Exception) {
                Log.e(TAG, "Could not arm the morning summary", e)
            }
        }

        fun summaryText(context: Context, d: SummaryData): String {
            if (d.interruptions == 0) return context.getString(R.string.summary_none)
            val kinds = d.minutesByKind.joinToString(", ") { context.getString(kindLabel(it.first)) }
            return context.resources.getQuantityString(
                R.plurals.summary_interrupted, d.interruptions, d.interruptions, d.totalMinutes, kinds,
            )
        }

        private fun kindLabel(kind: FaultKind) = when (kind) {
            FaultKind.NO_WATCH_DATA -> R.string.summary_kind_no_watch_data
            FaultKind.OSD_UNREACHABLE -> R.string.summary_kind_osd_unreachable
            FaultKind.OSD_WRONG_DATASOURCE -> R.string.summary_kind_osd_wrong_datasource
            FaultKind.OSD_REJECTS_DATA -> R.string.summary_kind_osd_rejects_data
            FaultKind.OSD_DATA_STALE -> R.string.summary_kind_osd_data_stale
            FaultKind.SERVICE_DOWN -> R.string.summary_kind_service_down
        }
    }
}
