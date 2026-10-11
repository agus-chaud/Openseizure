package com.seizureguard.wear.service

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor

/** DEC-070: a deliberate stop tells the phone, so it does not retry or alert; nothing else does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MonitoringStoppedMessageTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun running(notifier: () -> Unit): org.robolectric.android.controller.ServiceController<SeizureMonitorService> {
        shadowOf(app.getSystemService(SensorManager::class.java)).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        val controller = Robolectric.buildService(SeizureMonitorService::class.java).create()
        controller.get().monitoringStoppedNotifier = notifier
        controller.get().onStartCommand(SeizureMonitorService.startIntent(app), 0, 1)
        return controller
    }

    @Test fun userStop_sendsTheMessageOnce() {
        var sent = 0
        val c = running { sent++ }
        assertEquals("starting must not send it", 0, sent)
        c.get().onStartCommand(SeizureMonitorService.stopIntent(app), 0, 2)
        assertEquals(1, sent)
        assertFalse(SeizureMonitorService.monitoring.value)
    }

    @Test fun userStop_clearsTheFlagBeforeNotifying() {
        MonitorPrefs.setWasMonitoring(app, true)
        var flagWhenSent: Boolean? = null
        val c = running { flagWhenSent = MonitorPrefs.wasMonitoring(app) }
        c.get().onStartCommand(SeizureMonitorService.stopIntent(app), 0, 2)
        assertEquals(false, flagWhenSent)
    }

    @Test fun destroyWithoutAStop_neverSendsIt() {
        var sent = 0
        val c = running { sent++ }
        c.destroy() // the OS killed it, the process died: this is NOT a user stop
        assertEquals(0, sent)
    }

    @Test fun stickyRestart_neverSendsIt() {
        MonitorPrefs.setWasMonitoring(app, true)
        var sent = 0
        shadowOf(app.getSystemService(SensorManager::class.java)).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        val controller = Robolectric.buildService(SeizureMonitorService::class.java).create()
        controller.get().monitoringStoppedNotifier = { sent++ }
        controller.get().onStartCommand(null, 0, 1) // OS restart, resumes monitoring
        assertEquals(0, sent)
        controller.destroy()
        assertEquals(0, sent)
    }

    @Test fun aFailingSend_neverBlocksOrBreaksTheStop() {
        MonitorPrefs.setWasMonitoring(app, true)
        val c = running { throw IllegalStateException("GMS unavailable") }
        c.get().onStartCommand(SeizureMonitorService.stopIntent(app), 0, 2)
        assertFalse(SeizureMonitorService.monitoring.value)
        assertFalse("the stop still clears the resume flag", MonitorPrefs.wasMonitoring(app))
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
    }

    @Test fun defaultNotifier_isSafeWithoutPlayServices() {
        // The real seam (no override): must return immediately and never throw, even where GMS does not answer.
        shadowOf(app.getSystemService(SensorManager::class.java)).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        val c = Robolectric.buildService(SeizureMonitorService::class.java).create()
        c.get().onStartCommand(SeizureMonitorService.startIntent(app), 0, 1)
        c.get().onStartCommand(SeizureMonitorService.stopIntent(app), 0, 2)
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
    }
}
