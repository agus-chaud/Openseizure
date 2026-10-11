package com.seizureguard.wear.boot

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.seizureguard.wear.MainActivity
import com.seizureguard.wear.service.MonitorPrefs
import com.seizureguard.wear.service.SeizureMonitorService
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
    private val boot = Intent(Intent.ACTION_BOOT_COMPLETED)
    private val replaced = Intent(Intent.ACTION_MY_PACKAGE_REPLACED)

    private fun receiver(starter: (Context) -> Unit) = BootReceiver().also { it.starter = starter }
    private fun failureNotification() =
        shadowOf(nm).getNotification(SeizureMonitorService.RESTART_NEEDED_NOTIFICATION_ID)

    @Test fun shouldResume_followsFlag() {
        assertTrue(shouldResume(true))
        assertFalse(shouldResume(false))
    }

    @Test fun flagUnset_doesNothing() {
        var started = 0
        receiver { started++ }.onReceive(app, boot)
        assertEquals(0, started)
        assertNull(failureNotification())
    }

    @Test fun flagSet_bootCompleted_startsMonitoring() {
        MonitorPrefs.setWasMonitoring(app, true)
        var started = 0
        receiver { started++ }.onReceive(app, boot)
        assertEquals(1, started)
        assertNull(failureNotification())
    }

    @Test fun flagSet_packageReplaced_startsMonitoring() {
        MonitorPrefs.setWasMonitoring(app, true)
        var started = 0
        receiver { started++ }.onReceive(app, replaced)
        assertEquals(1, started)
    }

    @Test fun flagUnset_packageReplaced_doesNothing() {
        var started = 0
        receiver { started++ }.onReceive(app, replaced)
        assertEquals(0, started)
    }

    @Test fun flagSet_realStarter_launchesServiceWithActionStart() {
        MonitorPrefs.setWasMonitoring(app, true)
        BootReceiver().onReceive(app, boot)
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(SeizureMonitorService.ACTION_START, started.action)
        assertEquals(SeizureMonitorService::class.java.name, started.component?.className)
    }

    @Test fun otherAction_isIgnored() {
        MonitorPrefs.setWasMonitoring(app, true)
        var started = 0
        receiver { started++ }.onReceive(app, Intent(Intent.ACTION_USER_PRESENT))
        assertEquals(0, started)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun sameFlagAsService_noMigration() {
        // The service has always used this file and key; installed watches must keep working.
        app.getSharedPreferences("seizure_monitor_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("was_monitoring", true).commit()
        assertTrue(MonitorPrefs.wasMonitoring(app))
    }

    @Test fun startFailure_degradesToSilentNotification() {
        MonitorPrefs.setWasMonitoring(app, true)
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
        MonitorPrefs.setWasMonitoring(app, true)
        receiver { throw IllegalStateException("anything") }.onReceive(app, replaced)
        assertNotNull(failureNotification())
    }
}
