package com.seizureguard.phone.boot

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.seizureguard.phone.bridge.BridgeNotifications
import com.seizureguard.phone.bridge.BridgePrefs
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

    private fun receiver(starter: (Context) -> Unit) = BootReceiver().also { it.starter = starter }
    private fun failureNotification() = shadowOf(nm).getNotification(BridgeNotifications.START_FAILURE_NOTIFICATION_ID)

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

    @Test fun flagSet_startsBridge() {
        BridgePrefs.setWasBridging(app, true)
        var started = 0
        receiver { started++ }.onReceive(app, boot)
        assertEquals(1, started)
        assertNull(failureNotification())
    }

    @Test fun flagSet_realStarter_launchesService() {
        BridgePrefs.setWasBridging(app, true)
        BootReceiver().onReceive(app, boot)
        assertNotNull(shadowOf(app).nextStartedService)
    }

    @Test fun otherAction_isIgnored() {
        BridgePrefs.setWasBridging(app, true)
        var started = 0
        receiver { started++ }.onReceive(app, Intent(Intent.ACTION_USER_PRESENT))
        assertEquals(0, started)
    }

    @Test fun startFailure_degradesToSilentNotification() {
        BridgePrefs.setWasBridging(app, true)
        receiver { throw SecurityException("boot FGS not allowed") }.onReceive(app, boot)
        assertNotNull(failureNotification())
        val channel = nm.getNotificationChannel(BridgeNotifications.FAULT_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertEquals(false, channel.shouldVibrate())
        assertEquals(BridgeNotifications.FAULT_CHANNEL_ID, failureNotification().channelId)
        assertNotNull(failureNotification().contentIntent)
    }

    @Test fun boot_armsMorningSummary_evenIfResumeFails() {
        BridgePrefs.setWasBridging(app, true)
        receiver { throw SecurityException("boot FGS not allowed") }.onReceive(app, boot)
        assertNotNull(shadowOf(app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).nextScheduledAlarm)
    }

    @Test fun boot_doesNotArmSummary_whenNotBridging() {
        BridgePrefs.setWasBridging(app, false)
        receiver { }.onReceive(app, boot)
        assertNull(shadowOf(app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).nextScheduledAlarm)
    }
}
