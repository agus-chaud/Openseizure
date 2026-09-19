package com.seizureguard.phone.summary

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.seizureguard.phone.bridge.BridgeFault
import com.seizureguard.phone.bridge.BridgeNotifications
import com.seizureguard.phone.bridge.BridgePrefs
import com.seizureguard.phone.bridge.FaultKind
import com.seizureguard.phone.bridge.FaultLog
import com.seizureguard.phone.bridge.FaultPeriod
import com.seizureguard.phone.bridge.MemoryStore
import com.seizureguard.phone.bridge.ServiceLiveness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

private const val MIN = 60_000L
private const val H = 60 * MIN

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MorningSummaryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val alarms = shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    private fun p(kind: FaultKind, start: Long, end: Long?) = FaultPeriod(kind, start, end)
    private val start = 100 * H
    private val end = start + 12 * H

    // ── buildSummary ──────────────────────────────────────────────────────────

    @Test fun empty_meansNoInterruptions() {
        assertEquals(SummaryData(0, 0, emptyList()), buildSummary(emptyList(), start, end))
    }

    @Test fun overlappingPeriods_areMergedIntoOneInterruption() {
        val d = buildSummary(
            listOf(p(FaultKind.NO_WATCH_DATA, start + H, start + 2 * H), p(FaultKind.OSD_DATA_STALE, start + 90 * MIN, start + 3 * H)),
            start, end,
        )
        assertEquals(1, d.interruptions)
        assertEquals(120L, d.totalMinutes)
        assertEquals(listOf(FaultKind.OSD_DATA_STALE to 90L, FaultKind.NO_WATCH_DATA to 60L), d.minutesByKind)
    }

    @Test fun separatePeriods_countSeparately() {
        val d = buildSummary(
            listOf(p(FaultKind.OSD_UNREACHABLE, start + H, start + H + 10 * MIN), p(FaultKind.SERVICE_DOWN, start + 5 * H, start + 5 * H + 30 * MIN)),
            start, end,
        )
        assertEquals(2, d.interruptions)
        assertEquals(40L, d.totalMinutes)
    }

    @Test fun openPeriod_isClippedToNow() {
        val d = buildSummary(listOf(p(FaultKind.NO_WATCH_DATA, end - 30 * MIN, null)), start, end)
        assertEquals(SummaryData(1, 30, listOf(FaultKind.NO_WATCH_DATA to 30L)), d)
    }

    @Test fun windowClipping_ignoresOutsideAndTrimsEdges() {
        val d = buildSummary(
            listOf(
                p(FaultKind.OSD_UNREACHABLE, start - 5 * H, start - 4 * H), // before the window
                p(FaultKind.NO_WATCH_DATA, start - 10 * MIN, start + 20 * MIN), // straddles the start
                p(FaultKind.OSD_DATA_STALE, end + H, end + 2 * H), // after the window
            ),
            start, end,
        )
        assertEquals(SummaryData(1, 20, listOf(FaultKind.NO_WATCH_DATA to 20L)), d)
    }

    @Test fun serviceDown_isSummarised() {
        val d = buildSummary(listOf(p(FaultKind.SERVICE_DOWN, start + H, start + 2 * H)), start, end)
        assertEquals(SummaryData(1, 60, listOf(FaultKind.SERVICE_DOWN to 60L)), d)
    }

    @Test fun deadProcess_endsOpenFaultAtLastAlive_andAddsServiceDown() {
        val down = p(FaultKind.SERVICE_DOWN, start + 2 * H, null)
        val d = buildSummary(withServiceDown(listOf(p(FaultKind.OSD_DATA_STALE, start + H, null)), down), start, end)
        assertEquals(1, d.interruptions) // touching intervals merge
        assertEquals(660L, d.totalMinutes) // 1 h stale + 10 h dead until the window end
    }

    // ── schedule ──────────────────────────────────────────────────────────────

    private fun at(zone: TimeZone, h: Int, m: Int = 0) =
        Calendar.getInstance(zone).apply { set(2026, Calendar.MARCH, 10, h, m, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test fun nextTrigger_isTodayBefore8_andTomorrowAfter() {
        val z = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
        assertEquals(at(z, 8), nextSummaryTriggerMs(at(z, 7, 59), z))
        assertEquals(at(z, 8) + 24 * H, nextSummaryTriggerMs(at(z, 8), z)) // strictly after
        assertEquals(at(z, 8) + 24 * H, nextSummaryTriggerMs(at(z, 23), z))
    }

    @Test fun arm_schedulesInexactIdleAlarm_toTheReceiver() {
        MorningSummaryReceiver.arm(app, 1_000L)
        val alarm = alarms.nextScheduledAlarm
        assertNotNull(alarm)
        assertEquals(AlarmManager.RTC, alarm.type)
        assertEquals(nextSummaryTriggerMs(1_000L), alarm.triggerAtTime)
        assertTrue(shadowOf(alarm.operation).savedIntent.component?.className == MorningSummaryReceiver::class.java.name)
    }

    // ── receiver ──────────────────────────────────────────────────────────────

    private fun receiver(store: MemoryStore, now: Long) = MorningSummaryReceiver().apply {
        nowMs = { now }
        history = { FaultLog(store) { now } to ServiceLiveness(store, { now }, { 0L }) }
    }

    @Test fun receiver_postsOneSilentSummary_andRearms() {
        BridgePrefs.setWasBridging(app, true)
        val store = MemoryStore()
        val now = 10 * H
        FaultLog(store) { now }.add(p(FaultKind.OSD_DATA_STALE, now - 5 * H, now - 5 * H + 40 * MIN))
        ServiceLiveness(store, { now - MIN }, { 0L }).touch()
        receiver(store, now).onReceive(app, Intent())
        val n = shadowOf(nm).getNotification(BridgeNotifications.SUMMARY_NOTIFICATION_ID)
        assertEquals("Monitoring was interrupted 1 time (total 40 min): OSD not analysing.", n.extras.getString("android.text"))
        val ch = nm.getNotificationChannel("osd_bridge_summary")
        assertTrue(ch.importance <= NotificationManager.IMPORTANCE_LOW)
        assertNull(ch.sound)
        assertEquals(false, ch.shouldVibrate())
        assertNotNull(alarms.nextScheduledAlarm)
    }

    @Test fun receiver_quietNight_saysSo() {
        BridgePrefs.setWasBridging(app, true)
        val store = MemoryStore()
        ServiceLiveness(store, { 10 * H - MIN }, { 0L }).touch()
        receiver(store, 10 * H).onReceive(app, Intent())
        assertEquals("No interruptions last night.",
            shadowOf(nm).getNotification(BridgeNotifications.SUMMARY_NOTIFICATION_ID).extras.getString("android.text"))
    }

    @Test fun receiver_deadService_reportsPhoneStopped() {
        BridgePrefs.setWasBridging(app, true)
        val store = MemoryStore()
        val now = 20 * H
        ServiceLiveness(store, { now - 3 * H }, { 0L }).touch() // last seen alive 3 h ago, never restarted
        receiver(store, now).onReceive(app, Intent())
        assertEquals("Monitoring was interrupted 1 time (total 180 min): phone stopped.",
            shadowOf(nm).getNotification(BridgeNotifications.SUMMARY_NOTIFICATION_ID).extras.getString("android.text"))
    }

    @Test fun receiver_skipsWhenUserDidNotIntendBridging_andDoesNotRearm() {
        BridgePrefs.setWasBridging(app, false)
        receiver(MemoryStore(), 10 * H).onReceive(app, Intent())
        assertNull(shadowOf(nm).getNotification(BridgeNotifications.SUMMARY_NOTIFICATION_ID))
        assertNull(alarms.nextScheduledAlarm)
    }

    @Test fun faultKind_mapsEveryBridgeFault() {
        BridgeFault.values().filter { it != BridgeFault.NONE }.forEach { assertNotNull(FaultKind.from(it)) }
    }
}
