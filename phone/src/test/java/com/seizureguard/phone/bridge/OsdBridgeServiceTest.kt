package com.seizureguard.phone.bridge

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsdBridgeServiceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var now = 0L

    private fun ids() = shadowOf(nm).allNotifications.size
    private fun fault(id: Int) = shadowOf(nm).getNotification(id)
    private fun text(id: Int) = fault(id).extras.getString("android.text")

    // ── Fault notification policy (pure) ──────────────────────────────────────

    @Test fun policy_postsOnNewFault_repostsEvery60s_clearsOnNone() {
        val f = BridgeFault.OSD_UNREACHABLE
        assertEquals(FaultAction.NONE, faultAction(BridgeFault.NONE, BridgeFault.NONE, 0, 1_000))
        assertEquals(FaultAction.POST, faultAction(f, BridgeFault.NONE, 0, 1_000))
        assertEquals(FaultAction.NONE, faultAction(f, f, 1_000, 60_999))
        assertEquals(FaultAction.POST, faultAction(f, f, 1_000, 61_000))
        assertEquals(FaultAction.POST, faultAction(BridgeFault.NO_WATCH_DATA, f, 1_000, 2_000))
        assertEquals(FaultAction.CLEAR, faultAction(BridgeFault.NONE, f, 1_000, 2_000))
    }

    // ── BridgeNotifications against the real NotificationManager ──────────────

    @Test fun notifications_faultShowsText_reposts_thenClears() {
        val n = BridgeNotifications(app) { now }
        n.onHealthTick(BridgeFault.OSD_WRONG_DATASOURCE)
        assertEquals(app.getString(com.seizureguard.phone.R.string.fault_osd_wrong_datasource),
            text(BridgeNotifications.FAULT_NOTIFICATION_ID))

        val first = fault(BridgeNotifications.FAULT_NOTIFICATION_ID)
        now += HEALTH_TICK_MS
        n.onHealthTick(BridgeFault.OSD_WRONG_DATASOURCE)
        assertSame(first, fault(BridgeNotifications.FAULT_NOTIFICATION_ID)) // not re-posted at 10 s

        now += FAULT_REPOST_MS
        n.onHealthTick(BridgeFault.OSD_WRONG_DATASOURCE)
        assertNotSame(first, fault(BridgeNotifications.FAULT_NOTIFICATION_ID)) // re-posted after 60 s

        n.onHealthTick(BridgeFault.NONE)
        assertNull(fault(BridgeNotifications.FAULT_NOTIFICATION_ID))
    }

    @Test fun notifications_everyFaultHasDistinctActionableText() {
        val texts = BridgeFault.values().filter { it != BridgeFault.NONE }
            .map { app.getString(BridgeNotifications.faultTextRes(it)) }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test fun notifications_faultChannelIsHighAndErrorCategory() {
        BridgeNotifications(app) { now }.onHealthTick(BridgeFault.NO_WATCH_DATA)
        val ch = nm.getNotificationChannel(BridgeNotifications.FAULT_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
        val n = fault(BridgeNotifications.FAULT_NOTIFICATION_ID)
        assertEquals("err", n.category)
        assertEquals(true, (n.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
    }

    // ── Service wiring ────────────────────────────────────────────────────────

    @Test fun service_onCreateWiresDefaultObserver_andSeamIsReplaceable() {
        val service = Robolectric.buildService(OsdBridgeService::class.java).create().get()
        assertNotSame(BridgeObserver.NONE, service.observer)
        val recorded = mutableListOf<BridgeFault>()
        service.observer = object : BridgeObserver {
            override fun onAlarmDataPolled(body: String?) = Unit
            override fun onHealthTick(fault: BridgeFault) { recorded += fault }
        }
        service.observer.onHealthTick(BridgeFault.NO_WATCH_DATA)
        assertEquals(listOf(BridgeFault.NO_WATCH_DATA), recorded)
    }

    @Test fun service_start_entersForegroundWithStatusChannel_andDestroysCleanly() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val controller = Robolectric.buildService(OsdBridgeService::class.java, Intent(app, OsdBridgeService::class.java))
        controller.create().startCommand(0, 1)
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        assertEquals(NotificationManager.IMPORTANCE_LOW,
            nm.getNotificationChannel(BridgeNotifications.STATUS_CHANNEL_ID).importance)
        controller.destroy()
    }

    @Test fun service_startWithoutBluetoothPermission_alertsLoudly() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val controller = Robolectric.buildService(OsdBridgeService::class.java, Intent(app, OsdBridgeService::class.java))
        controller.create().startCommand(0, 1)
        assertNotNull(fault(BridgeNotifications.START_FAILURE_NOTIFICATION_ID))
        assertEquals(1, ids())
    }
}
