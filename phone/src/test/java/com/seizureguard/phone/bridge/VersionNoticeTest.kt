package com.seizureguard.phone.bridge

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

/**
 * Batch 8c: the silent "versions differ" notice, and R10-F3 (the version period and its notice survive
 * service stop/start and process restarts; only a MATCH from a real watch settings message closes them).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VersionNoticeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val store = MemoryStore()
    private var now = 1_000L
    private fun log() = FaultLog(store) { now }
    private fun settings(v: Int?) = WatchSettings(80, 25, v)

    private class FakeSink : VersionNoticeSink {
        val events = mutableListOf<String>()
        override fun show(result: VersionCompatibility) { events += "show:${result.name}" }
        override fun clear() { events += "clear" }
    }

    // ── recorder + sink ───────────────────────────────────────────────────────

    @Test fun mismatch_showsOnce_andRewordsOnlyWhenTheResultChanges() {
        val sink = FakeSink()
        val recorder = VersionMismatchRecorder(log(), notice = sink)
        recorder.onWatchSettings(settings(2))
        recorder.onWatchSettings(settings(2)) // same result: no repost
        recorder.onWatchSettings(settings(null)) // MISSING: milder wording
        recorder.onWatchSettings(settings(null))
        assertEquals(listOf("show:MISMATCH", "show:MISSING"), sink.events)
    }

    @Test fun match_clearsTheNotice_andClosesThePeriod() {
        val sink = FakeSink()
        val log = log()
        val recorder = VersionMismatchRecorder(log, notice = sink)
        recorder.onWatchSettings(settings(2))
        now = 3_000
        recorder.onWatchSettings(settings(1))
        assertEquals(listOf("show:MISMATCH", "clear"), sink.events)
        assertEquals(listOf(FaultPeriod(FaultKind.VERSION_MISMATCH, 1_000, 3_000)), log.periods())
    }

    @Test fun restartWithOpenPeriod_restoresTheSameWording_andOnlyMatchClearsIt() {
        VersionMismatchRecorder(log(), notice = FakeSink()).onWatchSettings(settings(null)) // MISSING, then the process dies
        val sink = FakeSink()
        val reborn = VersionMismatchRecorder(log(), notice = sink) // new process, same storage
        reborn.restore()
        assertEquals(listOf("show:MISSING"), sink.events)
        reborn.onWatchSettings(settings(null)) // still the same condition: nothing reposted
        assertEquals(listOf("show:MISSING"), sink.events)
        reborn.onWatchSettings(settings(1))
        assertEquals(listOf("show:MISSING", "clear"), sink.events)
        assertNull(log().openVersionResult())
    }

    @Test fun restartWithoutOpenPeriod_clearsAStaleNotice() {
        val sink = FakeSink()
        VersionMismatchRecorder(log(), notice = sink).restore()
        assertEquals(listOf("clear"), sink.events)
    }

    @Test fun matchAfterRestart_closesAndClears_evenThoughThisInstanceNeverShowedIt() {
        VersionMismatchRecorder(log(), notice = FakeSink()).onWatchSettings(settings(2))
        val sink = FakeSink()
        VersionMismatchRecorder(log(), notice = sink).onWatchSettings(settings(1)) // no restore() call
        assertEquals(listOf("clear"), sink.events)
        assertNull(log().openVersionResult())
    }

    @Test fun noticeFailure_neverBreaksRecording_andPersistenceFailure_neverHidesTheNotice() {
        val throwing = object : VersionNoticeSink {
            override fun show(result: VersionCompatibility) = throw IllegalStateException("nm down")
            override fun clear() = throw IllegalStateException("nm down")
        }
        val log = log()
        assertEquals(VersionCompatibility.MISMATCH, VersionMismatchRecorder(log, notice = throwing).onWatchSettings(settings(2)))
        assertEquals(1, log.periods().size) // still recorded

        val sink = FakeSink()
        val failing = FaultLog(object : StringStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String) = throw IllegalStateException("disk full")
        }) { now }
        VersionMismatchRecorder(failing, notice = sink).onWatchSettings(settings(2))
        assertEquals(listOf("show:MISMATCH"), sink.events)
    }

    // ── the notification itself: silent, passive, own id ──────────────────────

    private fun versionNotification() = shadowOf(nm).getNotification(BridgeNotifications.VERSION_NOTIFICATION_ID)
    private fun text(n: Notification) = n.extras.getString("android.text")

    @Test fun mismatchNotification_hasTheSpecifiedWording_andIsSilentOngoingAndPassive() {
        BridgeNotifications.postVersionNotice(app, VersionCompatibility.MISMATCH)
        val n = versionNotification()
        assertEquals("SeizureGuard on the watch and on this phone are different versions. Update both to the same version.", text(n))
        assertEquals("SeizureGuard: update needed", n.extras.getString("android.title"))
        assertEquals(BridgeNotifications.FAULT_CHANNEL_ID, n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.PRIORITY_LOW, n.priority)
        assertEquals(Notification.CATEGORY_STATUS, n.category)
        assertNull(n.fullScreenIntent)
        assertNull(n.sound)
        assertNull(n.vibrate)
    }

    @Test fun missingNotification_usesTheMilderWording() {
        BridgeNotifications.postVersionNotice(app, VersionCompatibility.MISSING)
        assertEquals("SeizureGuard on the watch looks out of date. Update it to the latest version.", text(versionNotification()))
    }

    @Test fun channel_isTheExistingSilentFaultChannel_withNoSoundVibrationOrLights() {
        BridgeNotifications.postVersionNotice(app, VersionCompatibility.MISMATCH)
        val ch = nm.getNotificationChannel(BridgeNotifications.FAULT_CHANNEL_ID)
        assertEquals("osd_bridge_fault_silent", ch.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, ch.importance) // below HIGH: no heads-up
        assertNull(ch.sound)
        assertFalse(ch.shouldVibrate())
        assertFalse(ch.shouldShowLights())
        assertFalse(ch.canShowBadge())
        // No other channel was created by the version notice (in particular none that can make sound).
        assertEquals(listOf(BridgeNotifications.FAULT_CHANNEL_ID), nm.notificationChannels.map { it.id })
    }

    @Test fun matchPostsNothing_andClearRemovesOnlyTheVersionNotification() {
        BridgeNotifications.postVersionNotice(app, VersionCompatibility.MATCH)
        assertNull(versionNotification())

        val fault = BridgeNotifications(app)
        fault.onHealthTick(BridgeFault.OSD_UNREACHABLE)
        BridgeNotifications.postVersionNotice(app, VersionCompatibility.MISMATCH)
        assertNotNull(shadowOf(nm).getNotification(BridgeNotifications.FAULT_NOTIFICATION_ID))
        assertNotNull(versionNotification())
        assertTrue(BridgeNotifications.VERSION_NOTIFICATION_ID !in listOf(
            BridgeNotifications.STATUS_NOTIFICATION_ID, BridgeNotifications.START_FAILURE_NOTIFICATION_ID,
            BridgeNotifications.FAULT_NOTIFICATION_ID, BridgeNotifications.SUMMARY_NOTIFICATION_ID,
        ))

        BridgeNotifications.clearVersionNotice(app)
        assertNull(versionNotification())
        assertNotNull(shadowOf(nm).getNotification(BridgeNotifications.FAULT_NOTIFICATION_ID)) // bridge fault untouched

        BridgeNotifications.postVersionNotice(app, VersionCompatibility.MISMATCH)
        fault.onHealthTick(BridgeFault.NONE) // bridge fault clears
        assertNull(shadowOf(nm).getNotification(BridgeNotifications.FAULT_NOTIFICATION_ID))
        assertNotNull(versionNotification()) // version notice not hidden by it
    }

    @Test fun notifier_followsTheRecorder_endToEnd() {
        val recorder = VersionMismatchRecorder(log(), notice = VersionNoticeNotifier(app))
        recorder.onWatchSettings(settings(2))
        assertNotNull(versionNotification())
        recorder.onWatchSettings(settings(null))
        assertEquals("SeizureGuard on the watch looks out of date. Update it to the latest version.", text(versionNotification()))
        recorder.onWatchSettings(settings(1))
        assertNull(versionNotification())
    }
}
