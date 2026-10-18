package com.seizureguard.wear.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.seizureguard.wear.R
import com.seizureguard.wear.boot.defaultMonitoringStarter
import com.seizureguard.wear.boot.resumeMonitoringIfWanted
import com.seizureguard.wear.schedule.MonitoringPausedMessage
import com.seizureguard.wear.schedule.MonitoringSchedule

/**
 * Entry point for the phone's `/osd/resume_monitoring` request (DEC-070). The phone sends it when the bridge has had
 * no watch data for hours and a watch is still connected: the watch app may have been killed while the user wanted
 * monitoring. Declared in the manifest so Play services can bind it while the app is not running; the in-service
 * listeners of [WearDataLayerManager] only exist while monitoring runs.
 *
 * It resumes through the same path as the boot receiver ([resumeMonitoringIfWanted]): only if `was_monitoring` is
 * true, and a refused start degrades to the silent restart-needed notice. Starting a foreground service from the
 * background is NOT a documented exemption for this entry point, so a refusal is expected on some Android versions;
 * it is verified on hardware in DV-11. Never throws: a crash here would take the process down at 3 am.
 */
class ResumeRequestListenerService : WearableListenerService() {

    internal var starter: (Context) -> Unit = defaultMonitoringStarter
    internal var pausedNotifier: (Context, Long) -> Unit = MonitoringPausedMessage::send
    internal var clock: () -> java.time.Instant = { java.time.Instant.now() }
    internal var zone: () -> java.time.ZoneId = { java.time.ZoneId.systemDefault() }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearDataLayerManager.PATH_RESUME_MONITORING) {
            super.onMessageReceived(event)
            return
        }
        Log.i(TAG, "Phone asked to resume monitoring")
        try {
            val started = resumeMonitoringIfWanted(
                this, "phone resume request", starter,
                noticeTextRes = R.string.notification_restart_needed_text_phone_request,
                clock = clock, zone = zone,
            )
            val now = clock()
            if (!started && !MonitoringSchedule.isInNightWindow(now, zone())) {
                // Daytime and not monitoring on purpose: re-announce the scheduled pause so the phone stops
                // escalating (covers a pause message lost at boot) (DEC-072).
                pausedNotifier(this, MonitoringSchedule.nextStart(now, zone()).toEpochMilli())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Resume request failed unexpectedly", e)
        }
    }

    private companion object {
        const val TAG = "ResumeRequestService"
    }
}
