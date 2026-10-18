package com.seizureguard.wear.data

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.wearable.MessageEvent
import com.seizureguard.wear.service.MonitorPrefs
import com.seizureguard.wear.service.SeizureMonitorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** DEC-070: the phone's `/osd/resume_monitoring` request wakes the watch app through a manifest-declared listener. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ResumeRequestListenerServiceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun event(path: String) = object : MessageEvent {
        override fun getRequestId() = 0
        override fun getPath() = path
        override fun getData() = ByteArray(0)
        override fun getSourceNodeId() = "phone"
    }

    private val zone = java.time.ZoneId.of("America/Argentina/Buenos_Aires")
    private fun at(h: Int, day: Int = 18) = java.time.ZonedDateTime.of(java.time.LocalDateTime.of(2026, 10, day, h, 0), zone).toInstant()
    private var now = at(14) // daytime unless a test says otherwise

    private fun service(starter: (Context) -> Unit) =
        Robolectric.buildService(ResumeRequestListenerService::class.java).create().get().also {
            it.starter = starter
            it.clock = { now }
            it.zone = { zone }
        }

    private fun notice() = shadowOf(nm).getNotification(SeizureMonitorService.RESTART_NEEDED_NOTIFICATION_ID)

    @Test fun flagTrue_resumeRequest_startsMonitoring() {
        MonitorPrefs.setWasMonitoring(app, true)
        var started = 0
        service { started++ }.onMessageReceived(event(WearDataLayerManager.PATH_RESUME_MONITORING))
        assertEquals(1, started)
        assertNull(notice())
    }

    @Test fun flagFalse_resumeRequest_isIgnored() {
        MonitorPrefs.setWasMonitoring(app, false)
        var started = 0
        service { started++ }.onMessageReceived(event(WearDataLayerManager.PATH_RESUME_MONITORING))
        assertEquals("a deliberate stop must stay stopped", 0, started)
        assertNull(notice())
    }

    @Test fun nightWindow_flagFalse_noManualStop_startsAMissedNight_andRecordsTheIntent() {
        now = at(1, day = 19)
        MonitorPrefs.setWasMonitoring(app, false)
        var started = 0
        service { started++ }.onMessageReceived(event(WearDataLayerManager.PATH_RESUME_MONITORING))
        assertEquals(1, started)
        assertTrue(MonitorPrefs.wasMonitoring(app))
        assertNull(notice())
    }

    @Test fun nightWindow_manualStopPending_isIgnored() {
        now = at(1, day = 19)
        MonitorPrefs.setWasMonitoring(app, false)
        MonitorPrefs.setManualStopUntilMs(app, at(22, day = 19).toEpochMilli())
        var started = 0
        service { started++ }.onMessageReceived(event(WearDataLayerManager.PATH_RESUME_MONITORING))
        assertEquals(0, started)
        assertFalse(MonitorPrefs.wasMonitoring(app))
    }

    @Test fun daytime_ignoredRequest_reannouncesTheScheduledPause() {
        MonitorPrefs.setWasMonitoring(app, false)
        var pausedUntil = -1L
        service { }.also { it.pausedNotifier = { _, until -> pausedUntil = until } }
            .onMessageReceived(event(WearDataLayerManager.PATH_RESUME_MONITORING))
        assertEquals(at(22).toEpochMilli(), pausedUntil)
    }

    @Test fun nightWindow_startedRequest_doesNotAnnounceAPause() {
        now = at(1, day = 19)
        var announced = false
        service { }.also { it.pausedNotifier = { _, _ -> announced = true } }
            .onMessageReceived(event(WearDataLayerManager.PATH_RESUME_MONITORING))
        assertTrue(!announced)
    }

    @Test fun daytime_flagFalse_isIgnored() {
        now = at(14)
        MonitorPrefs.setWasMonitoring(app, false)
        var started = 0
        service { started++ }.onMessageReceived(event(WearDataLayerManager.PATH_RESUME_MONITORING))
        assertEquals(0, started)
        assertFalse(MonitorPrefs.wasMonitoring(app))
    }

    @Test fun daytime_manualSessionFlagTrue_startsAsBefore() {
        now = at(14)
        MonitorPrefs.setWasMonitoring(app, true)
        var started = 0
        service { started++ }.onMessageReceived(event(WearDataLayerManager.PATH_RESUME_MONITORING))
        assertEquals(1, started)
    }

    @Test fun nightWindow_missedNight_refusedStart_postsThePhoneRequestNotice() {
        now = at(1, day = 19)
        service { throw IllegalStateException("not allowed") }.onMessageReceived(event(WearDataLayerManager.PATH_RESUME_MONITORING))
        assertEquals(
            app.getString(com.seizureguard.wear.R.string.notification_restart_needed_text_phone_request),
            notice().extras.getString("android.text"),
        )
    }

    @Test fun flagNeverSet_freshInstall_isIgnored() {
        var started = 0
        service { started++ }.onMessageReceived(event(WearDataLayerManager.PATH_RESUME_MONITORING))
        assertEquals(0, started)
    }

    @Test fun otherPaths_doNotStartAnything() {
        MonitorPrefs.setWasMonitoring(app, true)
        var started = 0
        val s = service { started++ }
        s.onMessageReceived(event(WearDataLayerManager.PATH_ALARM_STATE))
        s.onMessageReceived(event(WearDataLayerManager.PATH_SEND_SETTINGS))
        assertEquals(0, started)
    }

    @Test fun startRefused_postsTheSilentNoticeWithItsOwnText_andDoesNotCrash() {
        MonitorPrefs.setWasMonitoring(app, true)
        service { throw IllegalStateException("ForegroundServiceStartNotAllowedException") }
            .onMessageReceived(event(WearDataLayerManager.PATH_RESUME_MONITORING))
        val n = notice()
        assertNotNull(n)
        val channel = nm.getNotificationChannel(n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertEquals(
            app.getString(com.seizureguard.wear.R.string.notification_restart_needed_text_phone_request),
            n.extras.getString("android.text"),
        )
        assertTrue("the user still wants monitoring", MonitorPrefs.wasMonitoring(app))
    }

    @Test fun defaultStarter_launchesTheMonitorServiceWithActionStart() {
        MonitorPrefs.setWasMonitoring(app, true)
        Robolectric.buildService(ResumeRequestListenerService::class.java).create().get()
            .onMessageReceived(event(WearDataLayerManager.PATH_RESUME_MONITORING))
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(SeizureMonitorService.ACTION_START, started.action)
        assertEquals(SeizureMonitorService::class.java.name, started.component?.className)
    }

    // ── Manifest declaration ──────────────────────────────────────────────────

    private fun serviceTargets(path: String) = app.packageManager.queryIntentServices(
        Intent("com.google.android.gms.wearable.MESSAGE_RECEIVED").setData(Uri.parse("wear://some-node-id$path")), 0,
    ).map { it.serviceInfo.name }

    @Test fun manifest_bindsTheListenerOnlyForTheResumePath() {
        val name = ResumeRequestListenerService::class.java.name
        assertTrue(serviceTargets("/osd/resume_monitoring").contains(name))
        assertFalse("accel_data traffic must not wake the app", serviceTargets("/osd/accel_data").contains(name))
        assertFalse(serviceTargets("/osd/alarm_state").contains(name))
        assertFalse(serviceTargets("/other").contains(name))
    }

    @Test fun manifest_listenerIsExportedSoPlayServicesCanBindIt() {
        val info = app.packageManager.getServiceInfo(
            android.content.ComponentName(app, ResumeRequestListenerService::class.java), 0,
        )
        assertTrue(info.exported)
    }
}
