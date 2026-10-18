package com.seizureguard.wear.schedule

import java.time.Instant
import java.time.ZoneId

/** Which one-line schedule text the main screen shows. Pure, so the choice is unit-tested. */
enum class ScheduleLabel {
    /** "Monitoreo automático de 22:00 a 10:00". */
    SCHEDULE,

    /** "En pausa hasta las 22:00". */
    PAUSED;

    companion object {
        /**
         * PAUSED when monitoring is off and either the clock is outside the night window or the user stopped it
         * manually for the rest of the night ([manualStopUntilMs] in the future); SCHEDULE otherwise.
         */
        fun of(isMonitoring: Boolean, now: Instant, zone: ZoneId, manualStopUntilMs: Long): ScheduleLabel {
            if (isMonitoring) return SCHEDULE
            val outside = !MonitoringSchedule.isInNightWindow(now, zone)
            val manual = manualStopUntilMs > now.toEpochMilli()
            return if (outside || manual) PAUSED else SCHEDULE
        }
    }
}
