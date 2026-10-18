package com.seizureguard.wear.schedule

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Daily monitoring window (DEC-072). Pure functions over `java.time` so the window math, midnight crossing and
 * DST are unit-tested without a device. The window is [MONITOR_START] (inclusive) to [MONITOR_STOP] (exclusive)
 * and crosses midnight.
 */
object MonitoringSchedule {
    /** Local time at which monitoring starts every night. Signed 2026-10-18, CLINICAL_SIGNOFF.md. */
    val MONITOR_START: LocalTime = LocalTime.of(22, 0)

    /** Local time at which monitoring stops every morning. Signed 2026-10-18, CLINICAL_SIGNOFF.md. */
    val MONITOR_STOP: LocalTime = LocalTime.of(10, 0)

    /** true from 22:00 (inclusive) to 10:00 (exclusive) local time. */
    fun isInNightWindow(localTime: LocalTime): Boolean =
        !localTime.isBefore(MONITOR_START) || localTime.isBefore(MONITOR_STOP)

    fun isInNightWindow(now: Instant, zone: ZoneId): Boolean =
        isInNightWindow(now.atZone(zone).toLocalTime())

    /** First 22:00 local strictly after [now]. */
    fun nextStart(now: Instant, zone: ZoneId): Instant = nextAt(MONITOR_START, now, zone)

    /** First 10:00 local strictly after [now]. */
    fun nextStop(now: Instant, zone: ZoneId): Instant = nextAt(MONITOR_STOP, now, zone)

    private fun nextAt(time: LocalTime, now: Instant, zone: ZoneId): Instant {
        val local = now.atZone(zone)
        var candidate = local.toLocalDate().atTime(time).atZone(zone)
        // Walk by calendar day (not by 24 h) so a DST change cannot shift the wall-clock time.
        var guard = 0
        while (!candidate.toInstant().isAfter(now) && guard++ < 3) {
            candidate = ZonedDateTime.of(candidate.toLocalDate().plusDays(1), time, zone)
        }
        return candidate.toInstant()
    }
}
