package com.seizureguard.wear.service

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor

/** DEC-072: the scheduled 10:00 stop is a pause, a user stop holds until the next 22:00. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ScheduledPauseTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun reset() {
        MonitorPrefs.setWasMonitoring(app, false)
        MonitorPrefs.setManualStopUntilMs(app, 0L)
    }

    private fun running(
        stopped: () -> Unit = {},
        paused: (Long) -> Unit = {},
    ): org.robolectric.android.controller.ServiceController<SeizureMonitorService> {
        shadowOf(app.getSystemService(SensorManager::class.java)).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        val controller = Robolectric.buildService(SeizureMonitorService::class.java).create()
        controller.get().monitoringStoppedNotifier = stopped
        controller.get().monitoringPausedNotifier = paused
        controller.get().onStartCommand(SeizureMonitorService.startIntent(app), 0, 1)
        return controller
    }

    @Test fun scheduledStop_sendsPausedWithTheNextStart_andNotTheDeliberateStopMessage() {
        var stopped = 0
        var pausedUntil: Long? = null
        val c = running(stopped = { stopped++ }, paused = { pausedUntil = it })
        c.get().onStartCommand(SeizureMonitorService.stopIntent(app, pauseUntilMs = 1_790_000_000_000L), 0, 2)
        assertEquals(1_790_000_000_000L, pausedUntil)
        assertEquals("a scheduled pause is not a deliberate stop (DEC-070)", 0, stopped)
        assertFalse(SeizureMonitorService.monitoring.value)
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
    }

    @Test fun scheduledStop_clearsTheResumeFlag_butSetsNoManualStopHold() {
        MonitorPrefs.setWasMonitoring(app, true)
        val c = running()
        c.get().onStartCommand(SeizureMonitorService.stopIntent(app, pauseUntilMs = 1_790_000_000_000L), 0, 2)
        assertFalse(MonitorPrefs.wasMonitoring(app))
        assertEquals("tonight's 22:00 start must not be blocked", 0L, MonitorPrefs.manualStopUntilMs(app))
    }

    @Test fun manualStop_holdsUntilTheNext2200_andSendsTheDeliberateStopMessage() {
        var stopped = 0
        var paused = 0
        val c = running(stopped = { stopped++ }, paused = { paused++ })
        val before = System.currentTimeMillis()
        c.get().onStartCommand(SeizureMonitorService.stopIntent(app), 0, 2)
        assertEquals(1, stopped)
        assertEquals(0, paused)
        val hold = MonitorPrefs.manualStopUntilMs(app)
        val zone = ZoneId.systemDefault()
        assertTrue("in the future", hold > before)
        assertEquals(22, Instant.ofEpochMilli(hold).atZone(zone).hour)
        assertEquals(0, Instant.ofEpochMilli(hold).atZone(zone).minute)
    }

    @Test fun anyStart_clearsAManualStopHold() {
        MonitorPrefs.setManualStopUntilMs(app, System.currentTimeMillis() + 3_600_000L)
        running()
        assertEquals(0L, MonitorPrefs.manualStopUntilMs(app))
    }

    @Test fun aFailingPausedMessage_neverBreaksTheScheduledStop() {
        MonitorPrefs.setWasMonitoring(app, true)
        val c = running(paused = { throw IllegalStateException("GMS unavailable") })
        c.get().onStartCommand(SeizureMonitorService.stopIntent(app, pauseUntilMs = 1_790_000_000_000L), 0, 2)
        assertFalse(SeizureMonitorService.monitoring.value)
        assertFalse(MonitorPrefs.wasMonitoring(app))
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
    }

    @Test fun pausedMessageSeam_defaultIsSafeWithoutPlayServices() {
        val real = Robolectric.buildService(SeizureMonitorService::class.java).create()
        real.get().monitoringPausedNotifier(1_790_000_000_000L) // default seam: returns at once, never throws
        real.destroy()
    }
}
