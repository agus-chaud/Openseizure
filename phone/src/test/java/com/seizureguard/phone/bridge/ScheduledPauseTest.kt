package com.seizureguard.phone.bridge

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.wearable.MessageEvent
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

private const val H_MS = 60 * 60 * 1000L
private const val MIN_MS = 60 * 1000L

/**
 * DEC-072: the watch announces the daily 10:00 pause with `/osd/monitoring_paused` (payload = next start, epoch ms).
 * While it lasts nothing is retried or alerted and the status says so; afterwards the outage clock starts at the
 * scheduled resume with the signed DEC-070 constants unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledPauseTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val savedZone = TimeZone.getDefault()

    private var wall = 40 * 24 * H_MS + 10 * H_MS // "10:00" of some day
    private val resumeAt = wall + 12 * H_MS        // the announced 22:00 (fixed: wall moves in the tests)
    private val store = MemoryStore()
    private fun tracker(s: StringStore = store) = NoDataTracker(s, { wall }, { wall })
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

    // ── Pure policy ───────────────────────────────────────────────────────────

    @Test fun policy_noActionWhileThePauseLasts_evenFarPastTheAlertThreshold() {
        val start = 1_000_000L
        val until = start + 12 * H_MS
        assertEquals(NoDataAction.NONE, noDataAction(start + 11 * H_MS + 59 * MIN_MS, start, 0, false, false, null, until))
        // Without the pause the same instant would already have alerted.
        assertEquals(NoDataAction.ALERT, noDataAction(start + 11 * H_MS + 59 * MIN_MS, start, 0, false, false, null, 0L))
    }

    @Test fun policy_afterThePause_retryAtPlus3h_alertAtPlus8h_fromTheResumeTime() {
        val start = 1_000_000L
        val until = start + 12 * H_MS
        fun act(sinceResume: Long, retries: Int = 0) =
            noDataAction(until + sinceResume, start, retries, false, false, null, until)
        assertEquals(NoDataAction.NONE, act(0))
        assertEquals(NoDataAction.NONE, act(3 * H_MS - 1))
        assertEquals("01:00 when the pause ends at 22:00", NoDataAction.RETRY, act(3 * H_MS))
        assertEquals(NoDataAction.NONE, act(4 * H_MS - 1, retries = 1))
        assertEquals(NoDataAction.NONE, act(8 * H_MS - 1, retries = 3))
        assertEquals("06:00", NoDataAction.ALERT, act(8 * H_MS, retries = 3))
    }

    @Test fun policy_aDeliberateStopStillWinsOverEverything() {
        assertEquals(NoDataAction.NONE, noDataAction(100 * H_MS, 0, 0, false, true, null, 1))
    }

    @Test fun signedConstantsAreUnchanged() {
        assertEquals(3 * H_MS, NO_DATA_RETRY_AFTER_MS)
        assertEquals(1 * H_MS, NO_DATA_RETRY_INTERVAL_MS)
        assertEquals(3, NO_DATA_MAX_RETRIES)
        assertEquals(8 * H_MS, NO_DATA_ALERT_AFTER_MS)
    }

    // ── Tracker ───────────────────────────────────────────────────────────────

    @Test fun tracker_pauseBlocksRetriesAndAlert_thenCountsFromTheResume() {
        val t = tracker().apply { onServiceStart(); onValidData() }
        assertTrue(t.onScheduledPause(resumeAt))
        wall += 11 * H_MS + 59 * MIN_MS
        assertEquals(NoDataAction.NONE, t.decide())
        assertEquals(resumeAt, t.activePauseUntilMs())
        wall = resumeAt + 3 * H_MS
        assertEquals("pause over, no data: retry 3 h after the resume", NoDataAction.RETRY, t.decide())
        assertEquals(0L, t.activePauseUntilMs())
        t.recordRetry(NodeCheck.CONNECTED)
        wall = resumeAt + 8 * H_MS
        assertEquals(NoDataAction.ALERT, t.decide())
        assertEquals("the outage clock starts at the resume time", resumeAt, t.snapshot().outageStartMs)
    }

    @Test fun tracker_validDataClearsThePause() {
        val t = tracker().apply { onServiceStart() }
        t.onScheduledPause(resumeAt)
        wall += 2 * H_MS
        assertTrue("data during a pause ends it (the user started the watch by hand)", t.onValidData())
        assertEquals(0L, t.activePauseUntilMs())
        assertEquals(0L, t.snapshot().pausedUntilMs)
        wall += 8 * H_MS
        assertEquals("normal escalation from the last data", NoDataAction.ALERT, t.decide())
    }

    @Test fun tracker_pauseSurvivesARestart() {
        tracker().apply { onServiceStart(); onScheduledPause(resumeAt) }
        wall += 5 * H_MS
        val second = tracker() // new process
        assertEquals(resumeAt, second.activePauseUntilMs())
        assertEquals(NoDataAction.NONE, second.decide())
        wall = resumeAt + 3 * H_MS
        assertEquals(NoDataAction.RETRY, tracker().decide())
    }

    @Test fun tracker_rejectsUnusableEndTimes() {
        val t = tracker().apply { onServiceStart() }
        assertFalse("in the past", t.onScheduledPause(wall - 1))
        assertFalse("now", t.onScheduledPause(wall))
        assertFalse("days away: corrupt, would silence the escalation", t.onScheduledPause(wall + 17 * H_MS))
        assertFalse("max long", t.onScheduledPause(Long.MAX_VALUE))
        assertTrue(t.onScheduledPause(wall + MAX_SCHEDULED_PAUSE_MS))
        assertEquals(0L, tracker(MemoryStore()).apply { onServiceStart() }.snapshot().pausedUntilMs)
    }

    @Test fun tracker_aPauseResetsEarlierEscalationAndVoidsAnAlert() {
        val t = tracker().apply { onServiceStart() }
        t.recordRetry(NodeCheck.CONNECTED)
        t.recordAlertPosted()
        assertTrue(t.onScheduledPause(resumeAt))
        val s = t.snapshot()
        assertEquals(0, s.retriesDone)
        assertFalse(s.alertPosted)
    }

    @Test fun tracker_userStartClearsThePause() {
        val t = tracker().apply { onServiceStart(); onScheduledPause(resumeAt) }
        t.onUserStart()
        assertEquals(0L, t.activePauseUntilMs())
    }

    @Test fun tracker_deliberateStopIsSeparateFromThePause() {
        val t = tracker().apply { onServiceStart(); onScheduledPause(resumeAt) }
        t.onStoppedByUser()
        wall = resumeAt + 20 * H_MS
        assertEquals(NoDataAction.NONE, t.decide())
        assertTrue(t.snapshot().stoppedByUser)
    }

    @Test fun escalator_pauseTakesDownAPostedAlert_andIgnoresBadEndTimes() {
        val sink = FakeAlert()
        val log = FaultLog(MemoryStore()) { wall }
        val t = tracker().apply { onServiceStart() }
        val e = NoDataEscalator(t, FakeLink(), sink, log, relisten = {}, wallClockMs = { wall })
        e.onScheduledPause(wall - 5)
        assertEquals("a bad end time does nothing", 0, sink.cancels)
        e.onScheduledPause(resumeAt)
        assertEquals(1, sink.cancels)
        assertEquals(resumeAt, e.activePauseUntilMs())
        repeat(100) { wall += 10 * MIN_MS; if (wall < resumeAt) e.onTick() }
        assertTrue("no alert and no retry during the pause", sink.posts.isEmpty())
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    @Test fun parser_acceptsADecimalEpoch_rejectsEverythingElse() {
        assertEquals(1_790_000_000_000L, WatchMessageParser.parsePausedUntil("1790000000000".toByteArray()))
        assertNull(WatchMessageParser.parsePausedUntil(null))
        assertNull(WatchMessageParser.parsePausedUntil(ByteArray(0)))
        assertNull(WatchMessageParser.parsePausedUntil("0".toByteArray()))
        assertNull(WatchMessageParser.parsePausedUntil("-5".toByteArray()))
        assertNull(WatchMessageParser.parsePausedUntil("12.5".toByteArray()))
        assertNull(WatchMessageParser.parsePausedUntil("""{"until":1}""".toByteArray()))
        assertNull(WatchMessageParser.parsePausedUntil("1790000000000 ".toByteArray()))
        assertNull(WatchMessageParser.parsePausedUntil("9".repeat(16).toByteArray()))
        assertNull(WatchMessageParser.parsePausedUntil("abc".toByteArray()))
    }

    // ── Status text ───────────────────────────────────────────────────────────

    private var hasData = false
    private var pausedUntil = 0L
    private var since: Long? = 1_000L
    private fun notifier() = BridgeNotifications(app, { hasData }, { since }, { pausedUntil })
    private fun posted() = shadowOf(nm).getNotification(BridgeNotifications.STATUS_NOTIFICATION_ID)
    private fun title(n: Notification) = n.extras.getString("android.title")
    private fun text(n: Notification) = n.extras.getString("android.text")

    @Test fun status_duringThePause_isPaused_notNotMonitoring_andSilent() {
        pausedUntil = 40 * 24 * H_MS + 22 * H_MS
        val n = notifier()
        n.onHealthTick(BridgeFault.NO_WATCH_DATA)
        assertEquals("SeizureGuard: paused for the day", title(posted()))
        assertEquals("Monitoring resumes automatically at 22:00.", text(posted()))
        assertFalse(title(posted())!!.contains("NOT monitoring"))
        val channel = nm.getNotificationChannel(BridgeNotifications.STATUS_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(posted().sound)
        assertNull(posted().vibrate)
        assertTrue((posted().flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
    }

    @Test fun status_duringThePause_noFaultNotificationEither_whateverTheCause() {
        pausedUntil = 40 * 24 * H_MS + 22 * H_MS
        val n = notifier()
        for (fault in BridgeFault.values()) {
            n.onHealthTick(fault)
            assertEquals("SeizureGuard: paused for the day", title(posted()))
            assertNull("no fault notice for $fault", shadowOf(nm).getNotification(BridgeNotifications.FAULT_NOTIFICATION_ID))
        }
    }

    @Test fun status_afterThePause_withoutData_isTheNormalFaultText_sinceTheResume() {
        pausedUntil = 40 * 24 * H_MS + 22 * H_MS
        val n = notifier()
        n.onHealthTick(BridgeFault.NO_WATCH_DATA)
        pausedUntil = 0L // the pause ended and no data arrived
        since = 40 * 24 * H_MS + 22 * H_MS
        n.onHealthTick(BridgeFault.NO_WATCH_DATA)
        assertEquals("SeizureGuard: NOT monitoring", title(posted()))
        assertEquals("No data from the watch since ${BridgeNotifications.formatSince(since!!)}.", text(posted()))
    }

    @Test fun status_pauseEndedByData_returnsToHealthy() {
        pausedUntil = 40 * 24 * H_MS + 22 * H_MS
        val n = notifier()
        n.onHealthTick(BridgeFault.NONE)
        pausedUntil = 0L
        hasData = true
        n.onHealthTick(BridgeFault.NONE)
        assertEquals("SeizureGuard bridge running", title(posted()))
    }

    @Test fun status_unchangedPause_doesNotRepost() {
        pausedUntil = 40 * 24 * H_MS + 22 * H_MS
        val n = notifier()
        n.onHealthTick(BridgeFault.NONE)
        val first = posted()
        repeat(20) { n.onHealthTick(BridgeFault.NO_WATCH_DATA) }
        assertTrue(first === posted())
    }

    @Test fun statusView_isPureAndPauseOverridesAnyFault() {
        assertEquals(StatusView(BridgeFault.NONE, false, 5L), statusView(BridgeFault.OSD_UNREACHABLE, true, 5L))
        assertEquals(StatusView(BridgeFault.NONE, true), statusView(BridgeFault.NONE, true))
        assertNotEquals(statusView(BridgeFault.NONE, true, 5L), statusView(BridgeFault.NONE, true, 0L))
    }

    @Test fun formatClock_isLocalHourAndMinute() {
        assertEquals("22:00", BridgeNotifications.formatClock(40 * 24 * H_MS + 22 * H_MS))
        assertEquals("09:05", BridgeNotifications.formatClock(40 * 24 * H_MS + 9 * H_MS + 5 * MIN_MS))
    }

    // ── Service wiring ────────────────────────────────────────────────────────

    private fun message(path: String, payload: String) = object : MessageEvent {
        override fun getRequestId() = 0
        override fun getPath() = path
        override fun getData() = payload.toByteArray(Charsets.UTF_8)
        override fun getSourceNodeId() = "watch"
    }

    private fun service(t: NoDataTracker): OsdBridgeService {
        val escalator = NoDataEscalator(t, FakeLink(), FakeAlert(), FaultLog(MemoryStore()) { wall }, relisten = {}, wallClockMs = { wall })
        return Robolectric.buildService(OsdBridgeService::class.java).create().also { controller = it }.get()
            .also { it.escalation = escalator }
    }

    @Test fun pathConstantIsPinned() {
        // The watch declares the same literal (WearDataLayerManager.PATH_MONITORING_PAUSED).
        assertEquals("/osd/monitoring_paused", OsdBridgeService.PATH_MONITORING_PAUSED)
        assertEquals(1, COMPANION_CONTRACT_VERSION) // additive path: no contract bump (DEC-072)
    }

    @Test fun message_startsThePause() {
        val t = tracker().apply { onServiceStart() }
        service(t).onMessage(message(OsdBridgeService.PATH_MONITORING_PAUSED, resumeAt.toString()))
        assertEquals(resumeAt, t.activePauseUntilMs())
    }

    @Test fun malformedMessage_changesNothing() {
        val t = tracker().apply { onServiceStart() }
        service(t).onMessage(message(OsdBridgeService.PATH_MONITORING_PAUSED, "tomorrow"))
        assertEquals(0L, t.activePauseUntilMs())
        wall += 8 * H_MS
        assertEquals("fail-safe: the normal escalation still runs", NoDataAction.ALERT, t.decide())
    }

    @Test fun oldWatch_neverSendsIt_andBehavesAsBefore() {
        val t = tracker().apply { onServiceStart() }
        service(t)
        wall += 3 * H_MS
        assertEquals(NoDataAction.RETRY, t.decide())
        assertNotNull(t.snapshot())
    }
}
