package com.seizureguard.phone.bridge

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.seizureguard.phone.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The ongoing status notification (id 4101) tells the truth about data flow (DEC-071). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeStatusNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val id = BridgeNotifications.STATUS_NOTIFICATION_ID

    private var hasData = false
    private var since: Long? = null
    private fun notifier() = BridgeNotifications(app, { hasData }, { since })
    private fun posted() = shadowOf(nm).getNotification(id)
    private fun title(n: Notification) = n.extras.getString("android.title")
    private fun text(n: Notification) = n.extras.getString("android.text")
    private fun s(res: Int) = app.getString(res)

    @Test fun initialStatus_isNeutral_andClaimsNoDataFlow() {
        val n = BridgeNotifications.buildStatus(app)
        assertEquals(s(R.string.bridge_status_title), title(n))
        assertEquals("Waiting for watch data", text(n))
        assertFalse(text(n)!!.contains("Forwarding"))
        assertFalse(text(n)!!.contains("Receiving"))
    }

    @Test fun healthyOnlyAfterValidDataWasObserved() {
        val n = notifier()
        n.onHealthTick(BridgeFault.NONE) // no data yet: nothing to change, nothing posted
        assertNull(posted())
        hasData = true
        n.onHealthTick(BridgeFault.NONE)
        assertEquals("SeizureGuard bridge running", title(posted()))
        assertEquals("Receiving watch data", text(posted()))
    }

    @Test fun noWatchData_showsNotMonitoringAndSinceTime_thenRecoveryReturnsHealthy() {
        val n = notifier()
        hasData = true
        since = 1_700_000_000_000L
        n.onHealthTick(BridgeFault.NONE)
        n.onHealthTick(BridgeFault.NO_WATCH_DATA)
        val hhmm = BridgeNotifications.formatSince(since!!)
        assertEquals("SeizureGuard: NOT monitoring", title(posted()))
        assertEquals("No data from the watch since $hhmm.", text(posted()))

        n.onHealthTick(BridgeFault.NONE)
        assertEquals("SeizureGuard bridge running", title(posted()))
        assertEquals("Receiving watch data", text(posted()))
    }

    @Test fun noWatchData_withoutAnyKnownValidData_saysSinceBridgeStarted() {
        since = null
        notifier().onHealthTick(BridgeFault.NO_WATCH_DATA)
        assertEquals("No data from the watch since the bridge started.", text(posted()))
        since = 0L
        val n = notifier()
        n.onHealthTick(BridgeFault.NO_WATCH_DATA)
        assertEquals("No data from the watch since the bridge started.", text(posted()))
    }

    @Test fun otherFaults_showNotMonitoringWithTheFaultText() {
        val n = notifier()
        n.onHealthTick(BridgeFault.OSD_UNREACHABLE)
        assertEquals("SeizureGuard: NOT monitoring", title(posted()))
        assertEquals(s(R.string.fault_osd_unreachable), text(posted()))
    }

    @Test fun unchangedFault_doesNotRepost() {
        val n = notifier()
        n.onHealthTick(BridgeFault.NO_WATCH_DATA)
        val first = posted()
        repeat(30) { n.onHealthTick(BridgeFault.NO_WATCH_DATA) }
        assertSame(first, posted())
        n.onHealthTick(BridgeFault.OSD_UNREACHABLE)
        assertNotSame(first, posted())
    }

    @Test fun statusStaysSilent_onLowChannel() {
        val n = notifier()
        n.onHealthTick(BridgeFault.NO_WATCH_DATA)
        val ch = nm.getNotificationChannel(BridgeNotifications.STATUS_CHANNEL_ID)
        assertNotNull(ch)
        assertEquals(NotificationManager.IMPORTANCE_LOW, ch.importance)
        val p = posted()
        assertTrue((p.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
        assertTrue((p.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertNull(p.sound)
        assertNull(p.vibrate)
        assertNull(p.fullScreenIntent)
        assertTrue(p.priority <= Notification.PRIORITY_LOW)
    }

    @Test fun statusView_isPure() {
        assertEquals(StatusView(BridgeFault.NONE, false), statusView(BridgeFault.NONE, false))
        assertEquals(StatusView(BridgeFault.NONE, true), statusView(BridgeFault.NONE, true))
        assertEquals(statusView(BridgeFault.OSD_DATA_STALE, true), statusView(BridgeFault.OSD_DATA_STALE, false))
    }

    @Test fun state_reportsValidDataOnlyAfterAnAccelChunk() {
        val state = BridgeState(0L)
        assertFalse(state.hasReceivedValidData())
        state.onValidAccel(5L)
        assertTrue(state.hasReceivedValidData())
    }

    @Test fun formatSince_sameDay_isTimeOnly_otherDays_includeDate() {
        val cal = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.OCTOBER, 11, 9, 30, 0) }
        val now = cal.timeInMillis
        fun at(day: Int, h: Int, m: Int) =
            (cal.clone() as java.util.Calendar).apply { set(2026, java.util.Calendar.OCTOBER, day, h, m, 0) }.timeInMillis
        assertEquals("04:22", BridgeNotifications.formatSince(at(11, 4, 22), now))
        val prev = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        assertEquals(prev.format(Date(at(10, 23, 50))), BridgeNotifications.formatSince(at(10, 23, 50), now))
        assertTrue(BridgeNotifications.formatSince(at(10, 23, 50), now).contains("10"))
        assertEquals(prev.format(Date(at(8, 4, 22))), BridgeNotifications.formatSince(at(8, 4, 22), now))
        assertTrue(BridgeNotifications.formatSince(at(8, 4, 22), now).length > 5)
    }
}
