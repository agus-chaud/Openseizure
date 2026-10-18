package com.seizureguard.wear.boot

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.seizureguard.wear.MainActivity
import com.seizureguard.wear.schedule.MonitoringScheduler
import com.seizureguard.wear.service.MonitorPrefs
import com.seizureguard.wear.service.SeizureMonitorService
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootReceiverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val boot = Intent(Intent.ACTION_BOOT_COMPLETED)
    private val replaced = Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
    private val zone = ZoneId.of("America/Argentina/Buenos_Aires")

    private fun at(h: Int, mi: Int = 0, day: Int = 18): Instant =
        ZonedDateTime.of(LocalDateTime.of(2026, 10, day, h, mi), zone).toInstant()

    private var now = at(23) // inside the night window unless a test says otherwise

    private fun receiver(starter: (Context) -> Unit) = BootReceiver().also {
        it.starter = starter
        it.clock = { now }
        it.zone = { zone }
    }
    private fun failureNotification() =
        shadowOf(nm).getNotification(SeizureMonitorService.RESTART_NEEDED_NOTIFICATION_ID)

    @Test fun shouldResume_followsFlag() {
        assertTrue(shouldResume(true))
        assertFalse(shouldResume(false))
    }

    @Test fun shouldStartAtBoot_needsTheWindow_andNoPendingManualStop() {
        assertTrue(shouldStartAtBoot(true, 0L, 1_000L))
        assertFalse(shouldStartAtBoot(false, 0L, 1_000L))
        assertFalse("manual stop still in force", shouldStartAtBoot(true, 2_000L, 1_000L))
        assertTrue("manual stop expired", shouldStartAtBoot(true, 1_000L, 1_000L))
    }

    // ── Inside the night window ───────────────────────────────────────────────

    @Test fun insideWindow_bootCompleted_startsMonitoring_evenWithoutTheFlag() {
        var started = 0
        receiver { started++ }.onReceive(app, boot)
        assertEquals(1, started)
        assertTrue("records the intent to monitor", MonitorPrefs.wasMonitoring(app))
        assertNull(failureNotification())
    }

    @Test fun insideWindow_packageReplaced_startsMonitoring() {
        var started = 0
        receiver { started++ }.onReceive(app, replaced)
        assertEquals(1, started)
    }

    @Test fun afterMidnightInsideWindow_startsMonitoring() {
        now = at(3, 30, day = 19)
        var started = 0
        receiver { started++ }.onReceive(app, boot)
        assertEquals(1, started)
    }

    @Test fun at0959_starts_at1000_doesNot() {
        var started = 0
        now = at(9, 59, day = 19)
        receiver { started++ }.onReceive(app, boot)
        assertEquals(1, started)
        now = at(10, 0, day = 19)
        receiver { started++ }.onReceive(app, boot)
        assertEquals("10:00 is outside the window", 1, started)
    }

    @Test fun insideWindow_manualStopDuringThisNight_holdsAcrossReboot() {
        MonitorPrefs.setManualStopUntilMs(app, at(22, day = 19).toEpochMilli())
        var started = 0
        receiver { started++ }.onReceive(app, boot)
        assertEquals(0, started)
    }

    @Test fun insideWindow_aManualStopFromLastNight_noLongerHolds() {
        MonitorPrefs.setManualStopUntilMs(app, at(22, day = 18).toEpochMilli() - 1)
        var started = 0
        receiver { started++ }.onReceive(app, boot)
        assertEquals(1, started)
    }

    // ── Outside the night window ──────────────────────────────────────────────

    @Test fun outsideWindow_doesNotStart_evenWithTheFlag_butArmsTheAlarms() {
        now = at(14)
        MonitorPrefs.setWasMonitoring(app, true)
        var started = 0
        receiver { started++ }.onReceive(app, boot)
        assertEquals(0, started)
        assertNull(failureNotification())
        assertEquals(2, shadowOf(alarms).scheduledAlarms.size)
        val actions = shadowOf(alarms).scheduledAlarms.map { shadowOf(it.operation).savedIntent.action }.toSet()
        assertEquals(setOf(MonitoringScheduler.ACTION_MONITOR_START, MonitoringScheduler.ACTION_MONITOR_STOP), actions)
    }

    @Test fun insideWindow_alsoArmsTheAlarms() {
        receiver { }.onReceive(app, replaced)
        assertEquals(2, shadowOf(alarms).scheduledAlarms.size)
    }

    // ── Plumbing kept from DEC-069 ────────────────────────────────────────────

    @Test fun insideWindow_realStarter_launchesServiceWithActionStart() {
        BootReceiver().also { it.clock = { now }; it.zone = { zone } }.onReceive(app, boot)
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(SeizureMonitorService.ACTION_START, started.action)
        assertEquals(SeizureMonitorService::class.java.name, started.component?.className)
    }

    @Test fun otherAction_isIgnored() {
        var started = 0
        receiver { started++ }.onReceive(app, Intent(Intent.ACTION_USER_PRESENT))
        assertEquals(0, started)
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }

    @Test fun sameFlagAsService_noMigration() {
        // The service has always used this file and key; installed watches must keep working.
        app.getSharedPreferences("seizure_monitor_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("was_monitoring", true).commit()
        assertTrue(MonitorPrefs.wasMonitoring(app))
    }

    @Test fun startFailure_degradesToSilentNotification() {
        receiver { throw SecurityException("boot FGS not allowed") }.onReceive(app, boot)
        val notification = failureNotification()
        assertNotNull(notification)
        val channel = nm.getNotificationChannel(SeizureMonitorService.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertEquals(SeizureMonitorService.CHANNEL_ID, notification.channelId)
        assertNotNull(notification.contentIntent)
        val opened = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, opened.component?.className)
    }

    @Test fun startFailure_doesNotCrashTheReceiver() {
        receiver { throw IllegalStateException("anything") }.onReceive(app, replaced)
        assertNotNull(failureNotification())
    }
}
