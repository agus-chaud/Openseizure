package com.seizureguard.phone.bridge

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.wearable.MessageEvent
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

private const val HOUR = 60 * 60 * 1000L

/** The 8 h alert as the user sees it (channel, text, vibration) and its wiring into [OsdBridgeService.onMessage]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoDataAlertServiceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val savedZone = TimeZone.getDefault()

    private var wall = 40 * 24 * HOUR
    private val store = MemoryStore()
    private val tracker = NoDataTracker(store, { wall }, { wall })
    private val link = FakeLink()
    private val sink = FakeAlert()
    private val log = FaultLog(MemoryStore()) { wall }
    private val escalator = NoDataEscalator(tracker, link, sink, log, relisten = {}, wallClockMs = { wall })
    private var controller: ServiceController<OsdBridgeService>? = null

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        NoDataTracking.resetForTests()
    }

    @After fun tearDown() {
        TimeZone.setDefault(savedZone)
        controller?.destroy()
        NoDataTracking.resetForTests()
    }

    private fun alertNotification() = shadowOf(nm).getNotification(BridgeNotifications.NO_DATA_ALERT_NOTIFICATION_ID)
    private fun text(n: Notification) = n.extras.getString("android.text")

    // ── The notification ──────────────────────────────────────────────────────

    @Test fun alert_vibratesOnItsOwnChannel_noSound_noDoNotDisturbBypass() {
        BridgeNotifications.postNoDataAlert(app, 0L, NodeCheck.CONNECTED, 3)
        val ch = nm.getNotificationChannel(BridgeNotifications.NO_DATA_ALERT_CHANNEL_ID)
        assertEquals("osd_bridge_no_data_alert", ch.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
        assertNull(ch.sound)
        assertTrue(ch.shouldVibrate())
        assertTrue(BridgeNotifications.NO_DATA_ALERT_VIBRATION.contentEquals(ch.vibrationPattern))
        assertFalse(ch.canBypassDnd())
        assertFalse(ch.shouldShowLights())
    }

    @Test fun alert_isOngoing_alertsOnce_opensSetup_andIsAnErrorCategory() {
        BridgeNotifications.postNoDataAlert(app, 0L, NodeCheck.CONNECTED, 3)
        val n = alertNotification()
        assertEquals(4106, BridgeNotifications.NO_DATA_ALERT_NOTIFICATION_ID)
        assertEquals(BridgeNotifications.NO_DATA_ALERT_CHANNEL_ID, n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.CATEGORY_ERROR, n.category)
        assertNull("no full-screen takeover", n.fullScreenIntent)
        val opened = shadowOf(n.contentIntent).savedIntent
        assertEquals(com.seizureguard.phone.setup.SetupActivity::class.java.name, opened.component?.className)
    }

    @Test fun alert_textSaysSinceWhenCauseAndRetries() {
        val since = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 22); set(java.util.Calendar.MINUTE, 15) }.timeInMillis // today 22:15 local
        BridgeNotifications.postNoDataAlert(app, since, NodeCheck.NOT_CONNECTED, 3)
        val n = alertNotification()
        assertEquals("SeizureGuard: no watch data for 8 hours", n.extras.getString("android.title"))
        val body = text(n)!!
        assertTrue(body, body.contains("since 22:15"))
        assertTrue(body, body.contains("The watch is not connected to this phone. Check that it is charged, switched on and nearby."))
        assertTrue(body, body.endsWith("Retries tried: 3."))
    }

    @Test fun alert_causeTextFollowsTheNodeCheck() {
        val texts = NodeCheck.values().associateWith { c ->
            BridgeNotifications.postNoDataAlert(app, 0L, c, 0)
            text(alertNotification())!!
        }
        assertTrue(texts.getValue(NodeCheck.CONNECTED).contains("The watch is connected but SeizureGuard is not sending data. Open SeizureGuard on the watch and tap Start."))
        assertTrue(texts.getValue(NodeCheck.NOT_CONNECTED).contains("not connected to this phone"))
        assertEquals(3, texts.values.toSet().size)
    }

    @Test fun alert_leavesTheSilentFaultChannelAlone() {
        BridgeNotifications(app).onHealthTick(BridgeFault.NO_WATCH_DATA)
        BridgeNotifications.postNoDataAlert(app, 0L, NodeCheck.UNKNOWN, 3)
        val fault = nm.getNotificationChannel(BridgeNotifications.FAULT_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, fault.importance)
        assertNull(fault.sound)
        assertFalse(fault.shouldVibrate())
        assertNotNull(shadowOf(nm).getNotification(BridgeNotifications.FAULT_NOTIFICATION_ID))
        assertNotNull(alertNotification())
    }

    @Test fun cancel_removesTheAlert() {
        BridgeNotifications.postNoDataAlert(app, 0L, NodeCheck.CONNECTED, 3)
        BridgeNotifications.cancelNoDataAlert(app)
        assertNull(alertNotification())
    }

    // ── Wiring in the service ─────────────────────────────────────────────────

    private fun message(path: String, json: String = "") = object : MessageEvent {
        override fun getRequestId() = 0
        override fun getPath() = path
        override fun getData() = json.toByteArray(Charsets.UTF_8)
        override fun getSourceNodeId() = "watch"
    }

    private fun accelMsg(valid: Boolean = true) = message(
        OsdBridgeService.PATH_ACCEL,
        if (valid) """{"samples":[${(0 until 125).joinToString(",") { (it * 7 % 31 + 1).toString() }}]}""" else "not json",
    )

    private fun service(): OsdBridgeService =
        Robolectric.buildService(OsdBridgeService::class.java).create().also { controller = it }.get().also { it.escalation = escalator }

    @Test fun pathConstantsArePinned() {
        // The watch declares the same literals (WearDataLayerManager); a rename on one side would silence the feature.
        assertEquals("/osd/monitoring_stopped", OsdBridgeService.PATH_MONITORING_STOPPED)
        assertEquals("/osd/resume_monitoring", OsdBridgeService.PATH_RESUME_MONITORING)
    }

    @Test fun validAccel_endsTheEscalation_andTakesTheAlertDown() {
        tracker.onServiceStart()
        tracker.recordRetry(NodeCheck.CONNECTED)
        tracker.recordAlertPosted()
        service().onMessage(accelMsg())
        assertEquals(1, sink.cancels)
        assertEquals(0, tracker.snapshot().retriesDone)
        assertFalse(tracker.snapshot().alertPosted)
    }

    @Test fun malformedAccel_doesNotCountAsData() {
        tracker.onServiceStart()
        tracker.recordRetry(NodeCheck.CONNECTED)
        service().onMessage(accelMsg(valid = false))
        assertEquals(0, sink.cancels)
        assertEquals(1, tracker.snapshot().retriesDone)
    }

    @Test fun monitoringStopped_setsTheFlag_andCancelsTheAlert() {
        tracker.onServiceStart()
        tracker.recordAlertPosted()
        service().onMessage(message(OsdBridgeService.PATH_MONITORING_STOPPED))
        assertTrue(tracker.snapshot().stoppedByUser)
        assertFalse(tracker.snapshot().alertPosted)
        assertEquals(1, sink.cancels)
        wall += 20 * HOUR
        assertEquals(NoDataAction.NONE, tracker.decide())
    }

    @Test fun monitoringStopped_thenValidData_resumesNormalEscalation() {
        tracker.onServiceStart()
        val s = service()
        s.onMessage(message(OsdBridgeService.PATH_MONITORING_STOPPED))
        s.onMessage(accelMsg())
        assertFalse(tracker.snapshot().stoppedByUser)
        wall += 8 * HOUR
        assertEquals(NoDataAction.ALERT, tracker.decide())
    }

    @Test fun failingEscalation_neverBreaksMessageHandling() {
        val boom = object : WatchLink {
            override fun connectedNodes(): List<WatchNode>? = throw IllegalStateException("boom")
            override fun sendResume(nodeId: String): Boolean = throw IllegalStateException("boom")
        }
        val throwingTracker = NoDataTracker(object : StringStore {
            override fun get(key: String): String? = throw IllegalStateException("disk")
            override fun put(key: String, value: String) = throw IllegalStateException("disk")
        })
        val s = service().also { it.escalation = NoDataEscalator(throwingTracker, boom, sink, log, {}) }
        s.onMessage(accelMsg())
        s.onMessage(message(OsdBridgeService.PATH_MONITORING_STOPPED))
        runBlocking { s.escalationTick() }
    }

    @Test fun healthTick_runsTheEscalationStep() {
        tracker.onServiceStart()
        val s = service()
        wall += 8 * HOUR
        runBlocking { s.escalationTick() }
        assertEquals(1, sink.posts.size)
    }
}
