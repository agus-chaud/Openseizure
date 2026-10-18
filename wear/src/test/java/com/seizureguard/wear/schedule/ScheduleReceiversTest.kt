package com.seizureguard.wear.schedule

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.seizureguard.wear.service.MonitorPrefs
import com.seizureguard.wear.service.SeizureMonitorService
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** DEC-072: the two daily alarms, their re-arming, and what each one does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleReceiversTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val zone = ZoneId.of("America/Argentina/Buenos_Aires")

    private fun at(d: Int, h: Int, mi: Int = 0): Instant =
        ZonedDateTime.of(LocalDateTime.of(2026, 10, d, h, mi), zone).toInstant()

    private var now = at(18, 14) // Sunday afternoon

    @Before fun reset() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        MonitorPrefs.setWasMonitoring(app, false)
        MonitorPrefs.setManualStopUntilMs(app, 0L)
    }

    private fun scheduler() = MonitoringScheduler(app, { now }, { zone })

    private fun alarmFor(action: String) =
        shadowOf(alarms).scheduledAlarms.firstOrNull { shadowOf(it.operation).savedIntent.action == action }

    private fun alarmReceiver() = ScheduleAlarmReceiver().also {
        it.clock = { now }
        it.zone = { zone }
        it.isServiceMonitoring = { false }
    }

    private fun intent(action: String) = Intent(action)

    // ── Arming ────────────────────────────────────────────────────────────────

    @Test fun armAll_schedulesStartAt2200_andStopAt1000_asIdleSafeAlarms() {
        assertTrue(scheduler().armAll())
        val start = alarmFor(MonitoringScheduler.ACTION_MONITOR_START)!!
        val stop = alarmFor(MonitoringScheduler.ACTION_MONITOR_STOP)!!
        assertEquals(at(18, 22).toEpochMilli(), start.triggerAtMs)
        assertEquals(at(19, 10).toEpochMilli(), stop.triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, start.type)
        assertTrue(start.allowWhileIdle)
        assertTrue(stop.allowWhileIdle)
        assertNull(shadowOf(nm).getNotification(SeizureMonitorService.SCHEDULE_INEXACT_NOTIFICATION_ID))
    }

    @Test fun armAll_isIdempotent_oneAlarmPerAction() {
        scheduler().armAll()
        scheduler().armAll()
        assertEquals(2, shadowOf(alarms).scheduledAlarms.size)
    }

    @Test fun armAll_atNight_stopIsTomorrowMorning_startIsTomorrowNight() {
        now = at(18, 23)
        scheduler().armAll()
        assertEquals(at(19, 22).toEpochMilli(), alarmFor(MonitoringScheduler.ACTION_MONITOR_START)!!.triggerAtMs)
        assertEquals(at(19, 10).toEpochMilli(), alarmFor(MonitoringScheduler.ACTION_MONITOR_STOP)!!.triggerAtMs)
    }

    @Test fun exactAlarmNotAllowed_fallsBackToInexact_withASilentNotice() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(scheduler().armAll())
        assertNotNull("still armed, as inexact", alarmFor(MonitoringScheduler.ACTION_MONITOR_START))
        assertNotNull(alarmFor(MonitoringScheduler.ACTION_MONITOR_STOP))
        val notice = shadowOf(nm).getNotification(SeizureMonitorService.SCHEDULE_INEXACT_NOTIFICATION_ID)
        assertNotNull(notice)
        assertEquals(SeizureMonitorService.CHANNEL_ID, notice.channelId)
        val channel = nm.getNotificationChannel(SeizureMonitorService.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
    }

    @Test fun exactAlarmAllowedAgain_removesTheNotice() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        scheduler().armAll()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(scheduler().armAll())
        assertNull(shadowOf(nm).getNotification(SeizureMonitorService.SCHEDULE_INEXACT_NOTIFICATION_ID))
    }

    // ── Re-arm triggers ───────────────────────────────────────────────────────

    @Test fun rearm_onTimeChange_timezoneChange_andExactAlarmPermissionChange() {
        val actions = listOf(
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            ScheduleRearmReceiver.ACTION_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
        for (action in actions) {
            var armed = 0
            ScheduleRearmReceiver().also { it.scheduler = { armed++ } }.onReceive(app, intent(action))
            assertEquals(action, 1, armed)
        }
    }

    @Test fun rearm_ignoresOtherActions() {
        var armed = 0
        ScheduleRearmReceiver().also { it.scheduler = { armed++ } }.onReceive(app, intent(Intent.ACTION_USER_PRESENT))
        assertEquals(0, armed)
    }

    @Test fun rearm_realScheduler_replacesTheAlarmsForTheNewClock() {
        MonitoringScheduler(app, { now }, { zone }).armAll()
        val before = alarmFor(MonitoringScheduler.ACTION_MONITOR_START)!!.triggerAtMs
        // Timezone changed: the same instant, a different wall clock. The receiver uses the real clock and zone, so
        // here we only check it keeps exactly one pair of alarms.
        ScheduleRearmReceiver().onReceive(app, intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertEquals(2, shadowOf(alarms).scheduledAlarms.size)
        assertNotNull(before)
    }

    @Test fun rearmReceiver_survivesASchedulerFailure() {
        ScheduleRearmReceiver().also { it.scheduler = { throw IllegalStateException("boom") } }
            .onReceive(app, intent(Intent.ACTION_TIME_CHANGED))
    }

    // ── START alarm (22:00) ───────────────────────────────────────────────────

    @Test fun startAlarm_startsMonitoring_andRearmsBothAlarms() {
        now = at(18, 22)
        var started = 0
        alarmReceiver().also { it.starter = { started++ } }.onReceive(app, intent(MonitoringScheduler.ACTION_MONITOR_START))
        assertEquals(1, started)
        assertEquals(at(19, 22).toEpochMilli(), alarmFor(MonitoringScheduler.ACTION_MONITOR_START)!!.triggerAtMs)
        assertEquals(at(19, 10).toEpochMilli(), alarmFor(MonitoringScheduler.ACTION_MONITOR_STOP)!!.triggerAtMs)
    }

    @Test fun startAlarm_startsEvenAfterAManualStopLastNight_andClearsIt() {
        now = at(18, 22)
        MonitorPrefs.setWasMonitoring(app, false)
        MonitorPrefs.setManualStopUntilMs(app, at(18, 22).toEpochMilli())
        var started = 0
        alarmReceiver().also { it.starter = { started++ } }.onReceive(app, intent(MonitoringScheduler.ACTION_MONITOR_START))
        assertEquals(1, started)
        assertEquals(0L, MonitorPrefs.manualStopUntilMs(app))
        assertTrue("the intent to monitor is recorded for the DEC-070 resume and sticky restarts", MonitorPrefs.wasMonitoring(app))
    }

    @Test fun startAlarm_refusedStart_degradesToTheSilentNotice_andKeepsTheIntent() {
        now = at(18, 22)
        alarmReceiver().also { it.starter = { throw SecurityException("FGS not allowed") } }
            .onReceive(app, intent(MonitoringScheduler.ACTION_MONITOR_START))
        assertNotNull(shadowOf(nm).getNotification(SeizureMonitorService.RESTART_NEEDED_NOTIFICATION_ID))
        assertTrue(MonitorPrefs.wasMonitoring(app))
        assertNotNull("the schedule goes on", alarmFor(MonitoringScheduler.ACTION_MONITOR_START))
    }

    @Test fun startAlarm_realStarter_launchesTheServiceWithActionStart() {
        now = at(18, 22)
        alarmReceiver().onReceive(app, intent(MonitoringScheduler.ACTION_MONITOR_START))
        assertEquals(SeizureMonitorService.ACTION_START, shadowOf(app).nextStartedService.action)
    }

    // ── STOP alarm (10:00) ────────────────────────────────────────────────────

    @Test fun stopAlarm_withTheServiceRunning_stopsItAsAScheduledPause_withTheNextStart() {
        now = at(19, 10)
        var stoppedWith: Long? = null
        var paused = 0
        alarmReceiver().also {
            it.isServiceMonitoring = { true }
            it.stopper = { _, next -> stoppedWith = next }
            it.pausedNotifier = { _, _ -> paused++ }
        }.onReceive(app, intent(MonitoringScheduler.ACTION_MONITOR_STOP))
        assertEquals(at(19, 22).toEpochMilli(), stoppedWith)
        assertEquals("the service sends the message, not the receiver", 0, paused)
        assertEquals(at(20, 10).toEpochMilli(), alarmFor(MonitoringScheduler.ACTION_MONITOR_STOP)!!.triggerAtMs)
    }

    @Test fun stopAlarm_realStopper_sendsStopWithThePauseExtra_notAPlainStop() {
        now = at(19, 10)
        alarmReceiver().also { it.isServiceMonitoring = { true } }
            .onReceive(app, intent(MonitoringScheduler.ACTION_MONITOR_STOP))
        val sent = shadowOf(app).nextStartedService
        assertEquals(SeizureMonitorService.ACTION_STOP, sent.action)
        assertEquals(at(19, 22).toEpochMilli(), sent.getLongExtra(SeizureMonitorService.EXTRA_PAUSE_UNTIL_MS, 0L))
    }

    @Test fun stopAlarm_withTheServiceNotRunning_stillTellsThePhone_andClearsTheIntent() {
        now = at(19, 10)
        MonitorPrefs.setWasMonitoring(app, true)
        var pausedUntil: Long? = null
        alarmReceiver().also { it.pausedNotifier = { _, next -> pausedUntil = next } }
            .onReceive(app, intent(MonitoringScheduler.ACTION_MONITOR_STOP))
        assertEquals(at(19, 22).toEpochMilli(), pausedUntil)
        assertFalse(MonitorPrefs.wasMonitoring(app))
        assertEquals("a scheduled pause is not a manual stop", 0L, MonitorPrefs.manualStopUntilMs(app))
    }

    @Test fun stopAlarm_aRefusedServiceCall_neverCrashes_andKeepsTheSchedule() {
        now = at(19, 10)
        alarmReceiver().also {
            it.isServiceMonitoring = { true }
            it.stopper = { _, _ -> throw IllegalStateException("app in background") }
        }.onReceive(app, intent(MonitoringScheduler.ACTION_MONITOR_STOP))
        assertNotNull(alarmFor(MonitoringScheduler.ACTION_MONITOR_START))
    }

    @Test fun alarmReceiver_ignoresOtherActions() {
        var started = 0
        alarmReceiver().also { it.starter = { started++ } }.onReceive(app, intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(0, started)
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }
}
