package com.seizureguard.wear.schedule

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Window math for the 22:00-10:00 schedule (DEC-072). Pure JVM: no Android needed. */
class MonitoringScheduleTest {
    private val buenosAires = ZoneId.of("America/Argentina/Buenos_Aires") // no DST
    private val newYork = ZoneId.of("America/New_York") // DST: 2026-03-08 forward, 2026-11-01 back

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Instant =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone).toInstant()

    private fun local(i: Instant, zone: ZoneId) = i.atZone(zone)

    @Test fun signedConstants() {
        assertEquals(LocalTime.of(22, 0), MonitoringSchedule.MONITOR_START)
        assertEquals(LocalTime.of(10, 0), MonitoringSchedule.MONITOR_STOP)
    }

    @Test fun windowBoundaries() {
        assertFalse("21:59 is day", MonitoringSchedule.isInNightWindow(LocalTime.of(21, 59)))
        assertTrue("22:00 is night", MonitoringSchedule.isInNightWindow(LocalTime.of(22, 0)))
        assertTrue("23:59", MonitoringSchedule.isInNightWindow(LocalTime.of(23, 59)))
        assertTrue("midnight", MonitoringSchedule.isInNightWindow(LocalTime.MIDNIGHT))
        assertTrue("03:00", MonitoringSchedule.isInNightWindow(LocalTime.of(3, 0)))
        assertTrue("09:59 is night", MonitoringSchedule.isInNightWindow(LocalTime.of(9, 59)))
        assertFalse("10:00 is day", MonitoringSchedule.isInNightWindow(LocalTime.of(10, 0)))
        assertFalse("noon", MonitoringSchedule.isInNightWindow(LocalTime.NOON))
    }

    @Test fun windowFromInstant_usesTheGivenZone() {
        val instant = at(buenosAires, 2026, 10, 18, 23, 0)
        assertTrue(MonitoringSchedule.isInNightWindow(instant, buenosAires))
        // The same instant is 22:00 in New York (UTC-4 vs UTC-3 in October): still night. In UTC it is 02:00: night.
        assertTrue(MonitoringSchedule.isInNightWindow(instant, ZoneId.of("UTC")))
        assertFalse(MonitoringSchedule.isInNightWindow(at(buenosAires, 2026, 10, 18, 14, 0), buenosAires))
    }

    @Test fun nextStart_beforeAndAfter2200() {
        assertEquals(at(buenosAires, 2026, 10, 18, 22), MonitoringSchedule.nextStart(at(buenosAires, 2026, 10, 18, 21, 59), buenosAires))
        assertEquals("exactly 22:00 is not 'after': next day", at(buenosAires, 2026, 10, 19, 22), MonitoringSchedule.nextStart(at(buenosAires, 2026, 10, 18, 22), buenosAires))
        assertEquals(at(buenosAires, 2026, 10, 19, 22), MonitoringSchedule.nextStart(at(buenosAires, 2026, 10, 18, 22, 1), buenosAires))
    }

    @Test fun nextStart_acrossMidnightAndMorning() {
        assertEquals(at(buenosAires, 2026, 10, 18, 22), MonitoringSchedule.nextStart(at(buenosAires, 2026, 10, 18, 3), buenosAires))
        assertEquals(at(buenosAires, 2026, 10, 18, 22), MonitoringSchedule.nextStart(at(buenosAires, 2026, 10, 18, 10), buenosAires))
    }

    @Test fun nextStop_beforeAndAfter1000() {
        assertEquals(at(buenosAires, 2026, 10, 18, 10), MonitoringSchedule.nextStop(at(buenosAires, 2026, 10, 18, 9, 59), buenosAires))
        assertEquals(at(buenosAires, 2026, 10, 19, 10), MonitoringSchedule.nextStop(at(buenosAires, 2026, 10, 18, 10), buenosAires))
        assertEquals("from 23:00 the stop is tomorrow morning", at(buenosAires, 2026, 10, 19, 10), MonitoringSchedule.nextStop(at(buenosAires, 2026, 10, 18, 23), buenosAires))
        assertEquals(at(buenosAires, 2026, 10, 19, 10), MonitoringSchedule.nextStop(at(buenosAires, 2026, 10, 18, 22), buenosAires))
    }

    @Test fun dst_springForward_keepsWallClockTimes() {
        // 2026-03-08 02:00 EST jumps to 03:00 EDT: the night of 7 -> 8 March is one hour shorter.
        val before = at(newYork, 2026, 3, 7, 23)
        val stop = MonitoringSchedule.nextStop(before, newYork)
        assertEquals(LocalTime.of(10, 0), local(stop, newYork).toLocalTime())
        assertEquals(8, local(stop, newYork).dayOfMonth)
        val start = MonitoringSchedule.nextStart(before, newYork)
        assertEquals(LocalTime.of(22, 0), local(start, newYork).toLocalTime())
        assertEquals(8, local(start, newYork).dayOfMonth)
        // After the change the next start is still 22:00 local (EDT), not 23:00.
        val after = MonitoringSchedule.nextStart(at(newYork, 2026, 3, 8, 12), newYork)
        assertEquals(LocalTime.of(22, 0), local(after, newYork).toLocalTime())
        assertEquals(8, local(after, newYork).dayOfMonth)
    }

    @Test fun dst_fallBack_keepsWallClockTimes() {
        // 2026-11-01 02:00 EDT falls back to 01:00 EST: the night of 31 Oct -> 1 Nov is one hour longer.
        val before = at(newYork, 2026, 10, 31, 23)
        val stop = MonitoringSchedule.nextStop(before, newYork)
        assertEquals(LocalTime.of(10, 0), local(stop, newYork).toLocalTime())
        assertEquals(1, local(stop, newYork).dayOfMonth)
        val next = MonitoringSchedule.nextStart(at(newYork, 2026, 11, 1, 12), newYork)
        assertEquals(LocalTime.of(22, 0), local(next, newYork).toLocalTime())
        assertEquals(1, local(next, newYork).dayOfMonth)
    }

    @Test fun timezoneChange_movesTheNextAlarmForTheSameInstant() {
        val now = at(buenosAires, 2026, 10, 18, 12) // 15:00 UTC
        val ba = MonitoringSchedule.nextStart(now, buenosAires)
        val madrid = MonitoringSchedule.nextStart(now, ZoneId.of("Europe/Madrid"))
        assertEquals(LocalTime.of(22, 0), local(madrid, ZoneId.of("Europe/Madrid")).toLocalTime())
        assertTrue("22:00 in Madrid comes before 22:00 in Buenos Aires", madrid.isBefore(ba))
    }

    @Test fun nextAlarms_areAlwaysInTheFutureAndWithinOneDayAndAnHour() {
        var t = at(newYork, 2026, 3, 6, 0)
        repeat(24 * 4 * 5) { // every 15 minutes for 5 days across the spring-forward change
            val s = MonitoringSchedule.nextStart(t, newYork)
            val e = MonitoringSchedule.nextStop(t, newYork)
            assertTrue(s.isAfter(t) && e.isAfter(t))
            assertTrue(s.epochSecond - t.epochSecond <= 25 * 3600)
            assertTrue(e.epochSecond - t.epochSecond <= 25 * 3600)
            t = t.plusSeconds(15 * 60)
        }
    }

    @Test fun scheduleLabel_followsStateAndClock() {
        val day = at(buenosAires, 2026, 10, 18, 14)
        val night = at(buenosAires, 2026, 10, 18, 23)
        assertEquals(ScheduleLabel.PAUSED, ScheduleLabel.of(false, day, buenosAires, 0L))
        assertEquals(ScheduleLabel.SCHEDULE, ScheduleLabel.of(true, day, buenosAires, 0L)) // manual daytime session
        assertEquals(ScheduleLabel.SCHEDULE, ScheduleLabel.of(false, night, buenosAires, 0L))
        assertEquals(ScheduleLabel.SCHEDULE, ScheduleLabel.of(true, night, buenosAires, 0L))
        val stoppedUntil = at(buenosAires, 2026, 10, 19, 22).toEpochMilli()
        assertEquals("manual stop at night", ScheduleLabel.PAUSED, ScheduleLabel.of(false, night, buenosAires, stoppedUntil))
        assertEquals(ScheduleLabel.SCHEDULE, ScheduleLabel.of(false, night, buenosAires, night.toEpochMilli() - 1))
    }
}
